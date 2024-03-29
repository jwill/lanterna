/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2014 Martin
 */
package com.googlecode.lanterna.screen;

import com.googlecode.lanterna.CJKUtils;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TerminalPosition;

import java.io.IOException;

/**
 * This class implements some of the Screen logic that is not directly tied to the actual implementation of how the
 * Screen translate to the terminal. It keeps data structures for the front- and back buffers, the cursor location and
 * some other simpler states.
 * @author martin
 */
public abstract class AbstractScreen implements Screen {
    private TerminalPosition cursorPosition;
    private ScreenBuffer backBuffer;
    private ScreenBuffer frontBuffer;
    private final TextCharacter defaultCharacter;

    //How to deal with \t characters
    private TabBehaviour tabBehaviour;

    //Current size of the screen
    private TerminalSize terminalSize;

    //Pending resize of the screen
    private TerminalSize latestResizeRequest;

    public AbstractScreen(TerminalSize initialSize) {
        this(initialSize, DEFAULT_CHARACTER);
    }

    /**
     * Creates a new Screen on top of a supplied terminal, will query the terminal for its size. The screen is initially
     * blank. You can specify which character you wish to be used to fill the screen initially; this will also be the
     * character used if the terminal is enlarged and you don't set anything on the new areas.
     *
     * @param initialSize Size to initially create the Screen with (can be resized later)
     * @param defaultCharacter What character to use for the initial state of the screen and expanded areas
     */
    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public AbstractScreen(TerminalSize initialSize, TextCharacter defaultCharacter) {
        this.frontBuffer = new ScreenBuffer(initialSize, defaultCharacter);
        this.backBuffer = new ScreenBuffer(initialSize, defaultCharacter);
        this.defaultCharacter = defaultCharacter;
        this.cursorPosition = new TerminalPosition(0, 0);
        this.tabBehaviour = TabBehaviour.ALIGN_TO_COLUMN_4;
        this.terminalSize = initialSize;
        this.latestResizeRequest = null;
    }

    /**
     * @return Position where the cursor will be located after the screen has been refreshed or {@code null} if the
     * cursor is not visible
     */
    @Override
    public TerminalPosition getCursorPosition() {
        return cursorPosition;
    }

    /**
     * Moves the current cursor position or hides it. If the cursor is hidden and given a new position, it will be
     * visible after this method call.
     *
     * @param position 0-indexed column and row numbers of the new position, or if {@code null}, hides the cursor
     */
    @Override
    public void setCursorPosition(TerminalPosition position) {
        if(position == null) {
            //Skip any validation checks if we just want to hide the cursor
            this.cursorPosition = null;
            return;
        }
        if(position.getColumn() >= 0 && position.getColumn() < terminalSize.getColumns()
                && position.getRow() >= 0 && position.getRow() < terminalSize.getRows()) {
            this.cursorPosition = position;
        }
    }

    /**
     * Sets the behaviour for what to do about tab characters.
     *
     * @param tabBehaviour Tab behaviour to use
     * @see TabBehaviour
     */
    @Override
    public void setTabBehaviour(TabBehaviour tabBehaviour) {
        if(tabBehaviour != null) {
            this.tabBehaviour = tabBehaviour;
        }
    }

    /**
     * Gets the behaviour for what to do about tab characters.
     *
     * @return Tab behaviour that is used currently
     * @see TabBehaviour
     */
    @Override
    public TabBehaviour getTabBehaviour() {
        return tabBehaviour;
    }

    @Override
    public void setCharacter(TerminalPosition position, TextCharacter screenCharacter) {
        setCharacter(position.getColumn(), position.getRow(), screenCharacter);
    }

    @Override
    public TextGraphics newTextGraphics() {
        return new ScreenTextGraphics(this);
    }

    @Override
    public synchronized void setCharacter(int column, int row, TextCharacter screenCharacter) {
        //It would be nice if we didn't have to care about tabs at this level, but we have no such luxury
        if(screenCharacter.getCharacter() == '\t') {
            //Swap out the tab for a space
            screenCharacter = screenCharacter.withCharacter(' ');

            //Now see how many times we have to put spaces...
            for(int i = 0; i < tabBehaviour.replaceTabs("\t", column).length(); i++) {
                backBuffer.setCharacterAt(column + i, row, screenCharacter);
            }
        }
        else {
            //This is the normal case, no special character
            backBuffer.setCharacterAt(column, row, screenCharacter);
        }

        //Pad CJK character with a trailing space
        if(CJKUtils.isCharCJK(screenCharacter.getCharacter())) {
            backBuffer.setCharacterAt(column + 1, row, screenCharacter.withCharacter(' '));
        }
        //If there's a CJK character immediately to our left, reset it
        if(column > 0) {
            TextCharacter cjkTest = backBuffer.getCharacterAt(column - 1, row);
            if(cjkTest != null && CJKUtils.isCharCJK(cjkTest.getCharacter())) {
                backBuffer.setCharacterAt(column - 1, row, backBuffer.getCharacterAt(column - 1, row).withCharacter(' '));
            }
        }
    }

    @Override
    public synchronized TextCharacter getFrontCharacter(TerminalPosition position) {
        return getFrontCharacter(position.getColumn(), position.getRow());
    }

    @Override
    public TextCharacter getFrontCharacter(int column, int row) {
        return getCharacterFromBuffer(frontBuffer, column, row);
    }

    @Override
    public synchronized TextCharacter getBackCharacter(TerminalPosition position) {
        return getBackCharacter(position.getColumn(), position.getRow());
    }

    @Override
    public TextCharacter getBackCharacter(int column, int row) {
        return getCharacterFromBuffer(backBuffer, column, row);
    }

    @Override
    public void refresh() throws IOException {
        refresh(RefreshType.AUTOMATIC);
    }


    @Override
    public synchronized void clear() {
        backBuffer.setAll(defaultCharacter);
    }

    @Override
    public synchronized TerminalSize doResizeIfNecessary() {
        TerminalSize pendingResize = getAndClearPendingResize();
        if(pendingResize == null) {
            return null;
        }

        backBuffer = backBuffer.resize(pendingResize, defaultCharacter);
        frontBuffer = frontBuffer.resize(pendingResize, defaultCharacter);
        return pendingResize;
    }

    @Override
    public TerminalSize getTerminalSize() {
        return terminalSize;
    }

    protected ScreenBuffer getFrontBuffer() {
        return frontBuffer;
    }

    protected ScreenBuffer getBackBuffer() {
        return backBuffer;
    }

    private synchronized TerminalSize getAndClearPendingResize() {
        if(latestResizeRequest != null) {
            terminalSize = latestResizeRequest;
            latestResizeRequest = null;
            return terminalSize;
        }
        return null;
    }

    protected void addResizeRequest(TerminalSize newSize) {
        latestResizeRequest = newSize;
    }

    private TextCharacter getCharacterFromBuffer(ScreenBuffer buffer, int column, int row) {
        //If we are picking the padding of a CJK character, pick the actual CJK character instead of the padding
        if(column > 0 && CJKUtils.isCharCJK(buffer.getCharacterAt(column - 1, row).getCharacter())) {
            return buffer.getCharacterAt(column - 1, row);
        }
        return buffer.getCharacterAt(column, row);
    }
}

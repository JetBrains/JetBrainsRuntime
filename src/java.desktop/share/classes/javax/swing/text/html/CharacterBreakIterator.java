/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.swing.text.html;

import java.text.BreakIterator;
import java.text.CharacterIterator;

/**
 * Optimized version of {@code BreakIterator.getCharacterInstance()} for use with simple (non-multi-byte) text.
 */
class CharacterBreakIterator extends BreakIterator {
    private CharacterIterator text;
    private int beginIndex;
    private int endIndex;
    private int index;

    @Override
    public int first() {
        return index = beginIndex;
    }

    @Override
    public int last() {
        return index = endIndex;
    }

    @Override
    public int next(int n) {
        int targetIndex = index + n;
        if (targetIndex < beginIndex) {
            index = beginIndex;
            return BreakIterator.DONE;
        }
        if (targetIndex > endIndex) {
            index = endIndex;
            return BreakIterator.DONE;
        }
        return index = targetIndex;
    }

    @Override
    public int next() {
        if (index == endIndex) {
            return BreakIterator.DONE;
        }
        return ++index;
    }

    @Override
    public int previous() {
        if (index == beginIndex) {
            return BreakIterator.DONE;
        }
        return --index;
    }

    @Override
    public int following(int offset) {
        if (offset < beginIndex || offset > endIndex) {
            throw new IllegalArgumentException();
        }
        if (offset == endIndex) {
            return BreakIterator.DONE;
        }
        return index = offset + 1;
    }

    @Override
    public int preceding(int offset) {
        if (offset < beginIndex || offset > endIndex) {
            throw new IllegalArgumentException();
        }
        if (offset == beginIndex) {
            return BreakIterator.DONE;
        }
        return index = offset - 1;
    }

    @Override
    public int current() {
        return index;
    }

    @Override
    public CharacterIterator getText() {
        return text;
    }

    @Override
    public void setText(CharacterIterator newText) {
        text = newText;
        beginIndex = text.getBeginIndex();
        endIndex = text.getEndIndex();
        index = text.getIndex();
    }
}

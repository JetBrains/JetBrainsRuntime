/*
 * Copyright 2000-2019 JetBrains s.r.o.
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

/*
 * Class describing a key char
 */
public class KeyChar {

    // Char value
    private final char ch;
    // Dead or not
    private final boolean isDead;

    // Private constructor for an ordinal key char
    private KeyChar(char ch) {
        this.ch = ch;
        this.isDead = false;
    }

    // Private constructor for a dead key char
    private KeyChar(char ch, boolean isDead) {
        this.ch = ch;
        this.isDead = isDead;
    }

    // Helper method for an ordinal key char creation
    static KeyChar ch(char ch) {
        return new KeyChar(ch);
    }

    // Helper method for a dead key char creation
    static KeyChar dead(char ch) {
        return new KeyChar(ch, true);
    }

    // Returns dead marker
    boolean isDead() {
        return isDead;
    }

    // Returns char value
    char getChar() {
        return ch;
    }
}

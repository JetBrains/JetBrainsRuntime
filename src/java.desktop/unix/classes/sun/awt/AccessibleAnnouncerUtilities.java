/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.awt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

class AccessibleAnnouncerUtilities {
    private final static String GENERAL_KEY = "general";
    private final static String ENABLE_SPEECH_KEY = "enableSpeech";
    private final static String ACTIVE_PROFILE_KEY = "activeProfile";
    private final static String PROFILES_KEY = "profiles";
    private final static String SPEECH_SERVER_INFO_KEY = "speechServerInfo";
    private final static String VOICES_KEY = "voices";
    private final static String RATE_KEY = "rate";
    private final static String AVERAGE_PITCH_KEY = "average-pitch";
    private final static String FAMILY_KEY = "family";
    private final static String NAME_KEY = "name";
    private final static String LANG_KEY = "lang";
    private final static String DIALECT_KEY = "dialect";
    private final static String VARIANT_KEY = "variant";
    private final static String GAIN_KEY = "gain";
    private final static String VERBALIZE_PUNCTUATION_STYLE_KEY = "verbalizePunctuationStyle";
    private final static String DEFAULT_KEY = "default";

    private static Object getOrcaConf() {
        try {
            String path = System.getProperty("user.home") + "/.local/share/orca/user-settings.conf";
            String orcaConfSTR = Files.readString(Paths.get(path));

            return JSONParser.parse(orcaConfSTR);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static String getSpeechServerInfo(Object conf) {
        if (conf == null)
            return null;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return null;

        return JSONParser.getStringValue(conf, PROFILES_KEY, moduleName, SPEECH_SERVER_INFO_KEY, String.valueOf(0));
    }

    private static double getGain(Object conf) {
        if (conf == null)
            return -1;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return -1;

        Double val = JSONParser.getDoubleValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, GAIN_KEY);
        if (val != null)
            return val;
        return -1;
    }

    private static String getVariant(Object conf) {
        if (conf == null)
            return null;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return null;

        return JSONParser.getStringValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, FAMILY_KEY,
                VARIANT_KEY);
    }

    private static String getDialect(Object conf) {
        if (conf == null)
            return null;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return null;

        return JSONParser.getStringValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, FAMILY_KEY,
                DIALECT_KEY);
    }

    private static String getLang(Object conf) {
        if (conf == null)
            return null;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return null;

        return JSONParser.getStringValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, FAMILY_KEY, LANG_KEY);
    }

    private static String getName(Object conf) {
        if (conf == null)
            return null;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return null;

        return JSONParser.getStringValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, FAMILY_KEY, NAME_KEY);
    }

    private static double getAveragePitch(Object conf) {
        if (conf == null)
            return -1;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return -1;

        Double val = JSONParser.getDoubleValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY,
                AVERAGE_PITCH_KEY);
        if (val != null)
            return val;
        return -1;
    }

    private static double getRate(Object conf) {
        if (conf == null)
            return -1;

        String moduleName = getActiveProfile(conf);
        if (moduleName == null)
            return -1;

        Double val = JSONParser.getDoubleValue(conf, PROFILES_KEY, moduleName, VOICES_KEY, DEFAULT_KEY, RATE_KEY);
        if (val != null)
            return val;
        return -1;
    }

    private static String getActiveProfile(Object conf) {
        if (conf == null)
            return null;

        int counter = 0;
        String str = JSONParser.getStringValue(conf, GENERAL_KEY, ACTIVE_PROFILE_KEY, String.valueOf(counter));
        while (str != null) {
            if (!JSONParser.valueExists(conf, PROFILES_KEY, str)) {
                counter++;
                str = JSONParser.getStringValue(conf, GENERAL_KEY, ACTIVE_PROFILE_KEY, String.valueOf(counter));
            } else {
                return str;
            }
        }

        return null;
    }

    private static int getVerbalizePunctuationStyle(Object conf) {
        if (conf == null)
            return -1;

        Integer val = JSONParser.getIntValue(conf, GENERAL_KEY, VERBALIZE_PUNCTUATION_STYLE_KEY);
        if (val != null)
            return val;
        return -1;
    }

    private static boolean getEnableSpeech(Object conf) {
        if (conf == null)
            return false;

        Boolean val = JSONParser.getBooleanValue(conf, GENERAL_KEY, ENABLE_SPEECH_KEY);
        if (val != null)
            return val;
        return false;
    }

    private static class JSONParser {
        private JSONParser() {
        }

        public static Object parse(String json) throws ParseException {
            CharacterIterator it = new StringCharacterIterator(json);
            return Token.JSON.parse(it);
        }

        public static class ParseException extends Exception {
            private static final long serialVersionUID = 1992707L;

            ParseException(String message) {
                super(message);
            }
        }

        enum Token {
            JSON {
                Object parse(CharacterIterator it) throws ParseException {
                    Object o = ELEMENT.parse(it);
                    if (it.next() != CharacterIterator.DONE) {
                        error(it, "unexpected text following JSON end");
                    }
                    return o;
                }
            },
            ELEMENT {
                Object parse(CharacterIterator it) throws ParseException {
                    Token.skipWhitespace(it);
                    final Object o = VALUE.parse(it);
                    Token.skipWhitespace(it);
                    return o;
                }
            },
            VALUE {
                Object parse(CharacterIterator it) throws ParseException {
                    final char ch = it.current();
                    switch (ch) {
                        case '{' -> {
                            return OBJECT.parse(it);
                        }
                        case '[' -> {
                            return ARRAY.parse(it);
                        }
                        case '"' -> {
                            return STRING.parse(it);
                        }
                        case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                            return NUMBER.parse(it);
                        }
                        case 'n' -> {
                            Token.skipOver(it, 'n');
                            Token.skipOver(it, 'u');
                            Token.skipOver(it, 'l');
                            Token.skipOver(it, 'l');
                            return null;

                        }
                        case 't' -> {
                            Token.skipOver(it, 't');
                            Token.skipOver(it, 'r');
                            Token.skipOver(it, 'u');
                            Token.skipOver(it, 'e');
                            return Boolean.TRUE;
                        }
                        case 'f' -> {
                            Token.skipOver(it, 'f');
                            Token.skipOver(it, 'a');
                            Token.skipOver(it, 'l');
                            Token.skipOver(it, 's');
                            Token.skipOver(it, 'e');
                            return Boolean.FALSE;
                        }
                        default -> error(it, "unexpected character '" + ch + "' when parsing VALUE");
                    }

                    return null;
                }
            },
            OBJECT {
                Object parse(CharacterIterator it) throws ParseException {
                    skipOver(it, '{');
                    skipWhitespace(it);
                    final Map<String, Object> map = new HashMap<>();

                    while (it.current() != '}' && it.current() != CharacterIterator.DONE) {
                        Token.skipWhitespace(it);
                        final String name = (String) STRING.parse(it);
                        Token.skipWhitespace(it);
                        if (name == null)
                            error(it, "error parsing member name in object");
                        Token.skipOver(it, ':');
                        final Object value = ELEMENT.parse(it);
                        map.put(name, value);
                        if (it.current() == ',')
                            it.next();
                    }
                    skipOver(it, '}');

                    return map;
                }
            },
            ARRAY {
                Object parse(CharacterIterator it) throws ParseException {
                    skipOver(it, '[');
                    skipWhitespace(it);
                    final ArrayList<Object> array = new ArrayList<>();
                    while (it.current() != ']' && it.current() != CharacterIterator.DONE) {
                        final Object element = ELEMENT.parse(it);
                        array.add(element);
                        if (it.current() == ',')
                            it.next();
                    }
                    skipOver(it, ']');
                    return array.toArray();
                }
            },
            STRING {
                Object parse(CharacterIterator it) throws ParseException {
                    final StringBuilder builder = new StringBuilder();
                    skipOver(it, '"');

                    char ch = it.current();
                    while (ch != '"' && it.current() != CharacterIterator.DONE) {
                        if (ch == '\\') {
                            ch = it.next();
                            if (ch == CharacterIterator.DONE) {
                                error(it, "string unexpectedly terminated in escape sequence");
                            } else if (ch == 'u') {
                                final char hex1 = it.next();
                                final char hex2 = it.next();
                                final char hex3 = it.next();
                                final char hex4 = it.next();
                                if (hex1 == CharacterIterator.DONE || hex2 == CharacterIterator.DONE
                                        || hex3 == CharacterIterator.DONE || hex4 == CharacterIterator.DONE) {
                                    error(it, "string unexpectedly terminated in unicode sequence");
                                }
                                final char utf16 = (char) Integer.parseInt(String.valueOf(hex1) + hex2 + hex3 + hex4,
                                        16);
                                builder.append(utf16);
                            } else {
                                ch = switch (ch) {
                                    case 'b' -> '\b';
                                    case 'f' -> '\f';
                                    case 'n' -> '\n';
                                    case 'r' -> '\r';
                                    case 't' -> '\t';
                                    default -> ch;
                                };
                                builder.append(ch);
                            }
                        } else {
                            builder.append(ch);
                        }
                        ch = it.next();
                    }
                    skipOver(it, '"');
                    return builder.toString();
                }
            },
            NUMBER {
                Object parse(CharacterIterator it) throws ParseException {
                    final StringBuilder builder = new StringBuilder();
                    if (it.current() == '-') {
                        builder.append('-');
                        it.next();
                    }

                    while (Character.isDigit(it.current()) && it.current() != CharacterIterator.DONE) {
                        builder.append(it.current());
                        final char ch = it.next();
                        if (ch == '.') {
                            builder.append(ch);
                            it.next();
                        }
                    }

                    return Double.valueOf(builder.toString());
                }
            };

            abstract Object parse(CharacterIterator it) throws ParseException;

            private static void error(CharacterIterator it, String message) throws ParseException {
                StringBuilder builder = new StringBuilder(message);
                builder.append("; position ").append(it.getIndex()).append(" ");
                if (it.current() != CharacterIterator.DONE) {
                    builder.append("(at '");
                    for (int i = 0; it.current() != CharacterIterator.DONE && i < 15; i++) {
                        final char ch = it.current();
                        if (ch == '\n')
                            builder.append("\\n");
                        else
                            builder.append(ch);
                        it.next();
                    }
                    builder.append("')");
                } else {
                    builder.append("(at EOF)");
                }

                throw new ParseException(builder.toString());
            }

            private static void skipOver(CharacterIterator it, char ch) throws ParseException {
                if (it.current() != ch) {
                    error(it, "expected '" + ch + "', found '" + it.current() + "'");
                }
                it.next();
            }

            private static void skipWhitespace(CharacterIterator it) {
                while (Character.isWhitespace(it.current()))
                    it.next();
            }
        }

        public static String getStringValue(Object json, String... qualifiers) {
            Object value = getObjectValueImpl(json, qualifiers);
            return value instanceof String stringValue ? stringValue : null;
        }

        public static Boolean getBooleanValue(Object json, String... qualifiers) {
            Object value = getObjectValueImpl(json, qualifiers);
            return value instanceof Boolean booleanValue ? booleanValue : null;
        }

        public static Integer getIntValue(Object json, String... qualifiers) {
            Object value = getObjectValueImpl(json, qualifiers);
            return value instanceof Double doubleValue ? doubleValue.intValue() : null;
        }

        public static Double getDoubleValue(Object json, String... qualifiers) {
            Object value = getObjectValueImpl(json, qualifiers);
            return value instanceof Double booleanValue ? booleanValue : null;
        }

        private static boolean valueExists(Object json, String... qualifiers) {
            return getObjectValueImpl(json, qualifiers) != null;
        }

        @SuppressWarnings("rawtypes")
        private static Object getObjectValueImpl(Object json, String[] qualifiers) {
            Object current = json;
            for (String qual : qualifiers) {
                if (current instanceof Map currentMap) {
                    if (currentMap.containsKey(qual)) {
                        current = currentMap.get(qual);
                    } else {
                        return null;
                    }
                } else if (current instanceof Object[] array) {
                    int index = Integer.parseInt(qual);
                    if (index >= 0 && index < array.length) {
                        current = array[index];
                    } else {
                        return null;
                    }
                } else {
                    break;
                }
            }
            return current;
        }
    }
}

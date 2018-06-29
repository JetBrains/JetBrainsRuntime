/*
 * Copyright (c) 2018, Red Hat, Inc. and/or its affiliates.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 */

#include "precompiled.hpp"
#include "memory/resourceArea.hpp"
#include "utilities/bitMap.inline.hpp"
#include "unittest.hpp"

class BitMapCopyTest {

  template <class ResizableBitMapClass>
  static void fillBitMap(ResizableBitMapClass& map) {
    map.set_bit(0);
    map.set_bit(1);
    map.set_bit(3);
    map.set_bit(17);
    map.set_bit(512);
  }

public:
  static void testcopy0() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    fillBitMap(map1);

    ResourceBitMap map2(size);
    map2.copy_from(map1, 0, 0);
    EXPECT_TRUE(map2.is_empty());
    EXPECT_TRUE(map2.count_one_bits() == 0);
  }

  static void testcopy1() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    fillBitMap(map1);

    ResourceBitMap map2(size);
    map2.copy_from(map1, 0, 1);
    EXPECT_TRUE(map2.at(0));
    EXPECT_TRUE(map2.count_one_bits() == 1);

  }

  static void testcopy4() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    map1.set_range(0, 1024);

    ResourceBitMap map2(size);
    map2.copy_from(map1, 6, 10);
    EXPECT_FALSE(map2.at(5));
    EXPECT_TRUE(map2.at(6));
    EXPECT_TRUE(map2.at(7));
    EXPECT_TRUE(map2.at(8));
    EXPECT_TRUE(map2.at(9));
    EXPECT_FALSE(map2.at(10));
    EXPECT_TRUE(map2.count_one_bits() == 4);

  }

  static void testcopy8() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    map1.set_range(0, 1024);

    ResourceBitMap map2(size);
    map2.copy_from(map1, 0, 8);
    EXPECT_TRUE(map2.at(0));
    EXPECT_TRUE(map2.at(1));
    EXPECT_TRUE(map2.at(2));
    EXPECT_TRUE(map2.at(3));
    EXPECT_TRUE(map2.at(4));
    EXPECT_TRUE(map2.at(5));
    EXPECT_TRUE(map2.at(6));
    EXPECT_TRUE(map2.at(7));
    EXPECT_FALSE(map2.at(8));
    EXPECT_TRUE(map2.count_one_bits() == 8);

  }

  static void testcopy100() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    map1.set_range(0, 1024);

    ResourceBitMap map2(size);
    map2.copy_from(map1, 48, 148);
    EXPECT_FALSE(map2.at(47));
    EXPECT_TRUE(map2.at(48));
    EXPECT_TRUE(map2.at(147));
    EXPECT_FALSE(map2.at(148));
    EXPECT_TRUE(map2.count_one_bits() == 100);

  }

  static void testcopyall() {
    BitMap::idx_t size = 1024;
    ResourceMark rm;
    ResourceBitMap map1(size);
    fillBitMap(map1);

    ResourceBitMap map2(size);
    map2.set_range(0, 512);
    map2.copy_from(map1, 0, 1024);
    EXPECT_TRUE(map2.at(0));
    EXPECT_TRUE(map2.at(1));
    EXPECT_TRUE(map2.at(3));
    EXPECT_TRUE(map2.at(17));
    EXPECT_TRUE(map2.at(512));
    EXPECT_TRUE(map2.count_one_bits() == 5);

  }

};

TEST_VM(BitMap, copy0) {
  BitMapCopyTest::testcopy0();
}

TEST_VM(BitMap, copy1) {
  BitMapCopyTest::testcopy1();
}

TEST_VM(BitMap, copy4) {
  BitMapCopyTest::testcopy4();
}

TEST_VM(BitMap, copy8) {
  BitMapCopyTest::testcopy8();
}

TEST_VM(BitMap, copy100) {
  BitMapCopyTest::testcopy100();
}

TEST_VM(BitMap, copyall) {
  BitMapCopyTest::testcopyall();
}

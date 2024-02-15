/*
 * Copyright (c) 1997, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
#include "classfile/altHashing.hpp"
#include "classfile/classLoaderData.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/resourceArea.hpp"
#include "oops/symbol.hpp"
#include "runtime/atomic.hpp"
#include "runtime/os.hpp"
#include "runtime/signature.hpp"

Symbol::Symbol(const u1* name, int length, int refcount) {
  _refcount = refcount;
  _length = length;
  _identity_hash = (short)os::random();
  // _body[0..1] are allocated in the header just by coincidence in the current
  // implementation of Symbol. They are read by identity_hash(), so make sure they
  // are initialized.
  // No other code should assume that _body[0..1] are always allocated. E.g., do
  // not unconditionally read base()[0] as that will be invalid for an empty Symbol.
  _body[0] = _body[1] = 0;
  memcpy(_body, name, length);
}

void* Symbol::operator new(size_t sz, int len, TRAPS) throw() {
  int alloc_size = size(len)*wordSize;
  address res = (address) AllocateHeap(alloc_size, mtSymbol);
  return res;
}

void* Symbol::operator new(size_t sz, int len, Arena* arena, TRAPS) throw() {
  int alloc_size = size(len)*wordSize;
  address res = (address)arena->Amalloc_4(alloc_size);
  return res;
}

void Symbol::operator delete(void *p) {
  assert(((Symbol*)p)->refcount() == 0, "should not call this");
  FreeHeap(p);
}

// ------------------------------------------------------------------
// Symbol::starts_with
//
// Tests if the symbol starts with the specified prefix of the given
// length.
bool Symbol::starts_with(const char* prefix, int len) const {
  if (len > utf8_length()) return false;
  while (len-- > 0) {
    if (prefix[len] != (char) byte_at(len))
      return false;
  }
  assert(len == -1, "we should be at the beginning");
  return true;
}


// ------------------------------------------------------------------
// Symbol::index_of
//
// Finds if the given string is a substring of this symbol's utf8 bytes.
// Return -1 on failure.  Otherwise return the first index where str occurs.
int Symbol::index_of_at(int i, const char* str, int len) const {
  assert(i >= 0 && i <= utf8_length(), "oob");
  if (len <= 0)  return 0;
  char first_char = str[0];
  address bytes = (address) ((Symbol*)this)->base();
  address limit = bytes + utf8_length() - len;  // inclusive limit
  address scan = bytes + i;
  if (scan > limit)
    return -1;
  for (; scan <= limit; scan++) {
    scan = (address) memchr(scan, first_char, (limit + 1 - scan));
    if (scan == NULL)
      return -1;  // not found
    assert(scan >= bytes+i && scan <= limit, "scan oob");
    if (memcmp(scan, str, len) == 0)
      return (int)(scan - bytes);
  }
  return -1;
}


char* Symbol::as_C_string(char* buf, int size) const {
  if (size > 0) {
    int len = MIN2(size - 1, utf8_length());
    for (int i = 0; i < len; i++) {
      buf[i] = byte_at(i);
    }
    buf[len] = '\0';
  }
  return buf;
}

char* Symbol::as_C_string() const {
  int len = utf8_length();
  char* str = NEW_RESOURCE_ARRAY(char, len + 1);
  return as_C_string(str, len + 1);
}

char* Symbol::as_C_string_flexible_buffer(Thread* t,
                                                 char* buf, int size) const {
  char* str;
  int len = utf8_length();
  int buf_len = len + 1;
  if (size < buf_len) {
    str = NEW_RESOURCE_ARRAY(char, buf_len);
  } else {
    str = buf;
  }
  return as_C_string(str, buf_len);
}

void Symbol::print_utf8_on(outputStream* st) const {
  st->print("%s", as_C_string());
}

void Symbol::print_symbol_on(outputStream* st) const {
  char *s;
  st = st ? st : tty;
  {
    // ResourceMark may not affect st->print(). If st is a string
    // stream it could resize, using the same resource arena.
    ResourceMark rm;
    s = as_quoted_ascii();
    s = os::strdup(s);
  }
  if (s == NULL) {
    st->print("(null)");
  } else {
    st->print("%s", s);
    os::free(s);
  }
}

char* Symbol::as_quoted_ascii() const {
  const char *ptr = (const char *)&_body[0];
  int quoted_length = UTF8::quoted_ascii_length(ptr, utf8_length());
  char* result = NEW_RESOURCE_ARRAY(char, quoted_length + 1);
  UTF8::as_quoted_ascii(ptr, utf8_length(), result, quoted_length + 1);
  return result;
}

jchar* Symbol::as_unicode(int& length) const {
  Symbol* this_ptr = (Symbol*)this;
  length = UTF8::unicode_length((char*)this_ptr->bytes(), utf8_length());
  jchar* result = NEW_RESOURCE_ARRAY(jchar, length);
  if (length > 0) {
    UTF8::convert_to_unicode((char*)this_ptr->bytes(), result, length);
  }
  return result;
}

const char* Symbol::as_klass_external_name(char* buf, int size) const {
  if (size > 0) {
    char* str    = as_C_string(buf, size);
    int   length = (int)strlen(str);
    // Turn all '/'s into '.'s (also for array klasses)
    for (int index = 0; index < length; index++) {
      if (str[index] == '/') {
        str[index] = '.';
      }
    }
    return str;
  } else {
    return buf;
  }
}

const char* Symbol::as_klass_external_name() const {
  char* str    = as_C_string();
  int   length = (int)strlen(str);
  // Turn all '/'s into '.'s (also for array klasses)
  for (int index = 0; index < length; index++) {
    if (str[index] == '/') {
      str[index] = '.';
    }
  }
  return str;
}

static void print_class(outputStream *os, char *class_str, int len) {
  for (int i = 0; i < len; ++i) {
    if (class_str[i] == '/') {
      os->put('.');
    } else {
      os->put(class_str[i]);
    }
  }
}

static void print_array(outputStream *os, char *array_str, int len) {
  int dimensions = 0;
  for (int i = 0; i < len; ++i) {
    if (array_str[i] == '[') {
      dimensions++;
    } else if (array_str[i] == 'L') {
      // Expected format: L<type name>;. Skip 'L' and ';' delimiting the type name.
      print_class(os, array_str+i+1, len-i-2);
      break;
    } else {
      os->print("%s", type2name(char2type(array_str[i])));
    }
  }
  for (int i = 0; i < dimensions; ++i) {
    os->print("[]");
  }
}

void Symbol::print_as_signature_external_return_type(outputStream *os) {
  for (SignatureStream ss(this); !ss.is_done(); ss.next()) {
    if (ss.at_return_type()) {
      if (ss.is_array()) {
        print_array(os, (char*)ss.raw_bytes(), (int)ss.raw_length());
      } else if (ss.is_object()) {
        // Expected format: L<type name>;. Skip 'L' and ';' delimiting the class name.
        print_class(os, (char*)ss.raw_bytes()+1, (int)ss.raw_length()-2);
      } else {
        os->print("%s", type2name(ss.type()));
      }
    }
  }
}

void Symbol::print_as_signature_external_parameters(outputStream *os) {
  bool first = true;
  for (SignatureStream ss(this); !ss.is_done(); ss.next()) {
    if (ss.at_return_type()) break;
    if (!first) { os->print(", "); }
    if (ss.is_array()) {
      print_array(os, (char*)ss.raw_bytes(), (int)ss.raw_length());
    } else if (ss.is_object()) {
      // Skip 'L' and ';'.
      print_class(os, (char*)ss.raw_bytes()+1, (int)ss.raw_length()-2);
    } else {
      os->print("%s", type2name(ss.type()));
    }
    first = false;
  }
}

// Alternate hashing for unbalanced symbol tables.
unsigned int Symbol::new_hash(juint seed) {
  ResourceMark rm;
  // Use alternate hashing algorithm on this symbol.
  return AltHashing::halfsiphash_32(seed, (const uint8_t*)as_C_string(), utf8_length());
}

void Symbol::increment_refcount() {
  // Only increment the refcount if non-negative.  If negative either
  // overflow has occurred or it is a permanent symbol in a read only
  // shared archive.
  if (_refcount >= 0) { // not a permanent symbol
    Atomic::inc(&_refcount);
    NOT_PRODUCT(Atomic::inc(&_total_count);)
  }
}

void Symbol::decrement_refcount() {
  if (_refcount >= 0) { // not a permanent symbol
    short new_value = Atomic::add(short(-1), &_refcount);
#ifdef ASSERT
    if (new_value == -1) { // we have transitioned from 0 -> -1
      print();
      assert(false, "reference count underflow for symbol");
    }
#endif
    (void)new_value;
  }
}

void Symbol::metaspace_pointers_do(MetaspaceClosure* it) {
  if (log_is_enabled(Trace, cds)) {
    LogStream trace_stream(Log(cds)::trace());
    trace_stream.print("Iter(Symbol): %p ", this);
    print_value_on(&trace_stream);
    trace_stream.cr();
  }
}

void Symbol::print_on(outputStream* st) const {
  if (this == NULL) {
    st->print_cr("NULL");
  } else {
    st->print("Symbol: '");
    print_symbol_on(st);
    st->print("'");
    st->print(" count %d", refcount());
  }
}

// The print_value functions are present in all builds, to support the
// disassembler and error reporting.
void Symbol::print_value_on(outputStream* st) const {
  if (this == NULL) {
    st->print("NULL");
  } else {
    st->print("'");
    for (int i = 0; i < utf8_length(); i++) {
      st->print("%c", byte_at(i));
    }
    st->print("'");
  }
}

bool Symbol::is_valid(Symbol* s) {
  if (!is_aligned(s, sizeof(MetaWord))) return false;
  if ((size_t)s < os::min_page_size()) return false;

  if (!os::is_readable_range(s, s + 1)) return false;

  // Symbols are not allocated in Java heap.
  if (Universe::heap()->is_in_reserved(s)) return false;

  int len = s->utf8_length();
  if (len < 0) return false;

  jbyte* bytes = (jbyte*) s->bytes();
  return os::is_readable_range(bytes, bytes + len);
}

// SymbolTable prints this in its statistics
NOT_PRODUCT(int Symbol::_total_count = 0;)

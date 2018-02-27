/*
 * Copyright (c) 2001, 2014, Oracle and/or its affiliates. All rights reserved.
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
#include "memory/allocation.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/numberSeq.hpp"

AbsSeq::AbsSeq(double alpha) :
  _num(0), _sum(0.0), _sum_of_squares(0.0),
  _davg(0.0), _dvariance(0.0), _alpha(alpha) {
}

void AbsSeq::add(double val) {
  if (_num == 0) {
    // if the sequence is empty, the davg is the same as the value
    _davg = val;
    // and the variance is 0
    _dvariance = 0.0;
  } else {
    // otherwise, calculate both
    _davg = (1.0 - _alpha) * val + _alpha * _davg;
    double diff = val - _davg;
    _dvariance = (1.0 - _alpha) * diff * diff + _alpha * _dvariance;
  }
}

double AbsSeq::avg() const {
  if (_num == 0)
    return 0.0;
  else
    return _sum / total();
}

double AbsSeq::variance() const {
  if (_num <= 1)
    return 0.0;

  double x_bar = avg();
  double result = _sum_of_squares / total() - x_bar * x_bar;
  if (result < 0.0) {
    // due to loss-of-precision errors, the variance might be negative
    // by a small bit

    //    guarantee(-0.1 < result && result < 0.0,
    //        "if variance is negative, it should be very small");
    result = 0.0;
  }
  return result;
}

double AbsSeq::sd() const {
  double var = variance();
  guarantee( var >= 0.0, "variance should not be negative" );
  return sqrt(var);
}

double AbsSeq::davg() const {
  return _davg;
}

double AbsSeq::dvariance() const {
  if (_num <= 1)
    return 0.0;

  double result = _dvariance;
  if (result < 0.0) {
    // due to loss-of-precision errors, the variance might be negative
    // by a small bit

    guarantee(-0.1 < result && result < 0.0,
               "if variance is negative, it should be very small");
    result = 0.0;
  }
  return result;
}

double AbsSeq::dsd() const {
  double var = dvariance();
  guarantee( var >= 0.0, "variance should not be negative" );
  return sqrt(var);
}

NumberSeq::NumberSeq(double alpha) :
  AbsSeq(alpha), _maximum(0.0), _last(0.0) {
}

bool NumberSeq::check_nums(NumberSeq *total, int n, NumberSeq **parts) {
  for (int i = 0; i < n; ++i) {
    if (parts[i] != NULL && total->num() != parts[i]->num())
      return false;
  }
  return true;
}

void NumberSeq::add(double val) {
  AbsSeq::add(val);

  _last = val;
  if (_num == 0) {
    _maximum = val;
  } else {
    if (val > _maximum)
      _maximum = val;
  }
  _sum += val;
  _sum_of_squares += val * val;
  ++_num;
}


TruncatedSeq::TruncatedSeq(int length, double alpha):
  AbsSeq(alpha), _length(length), _next(0) {
  _sequence = NEW_C_HEAP_ARRAY(double, _length, mtInternal);
  for (int i = 0; i < _length; ++i)
    _sequence[i] = 0.0;
}

TruncatedSeq::~TruncatedSeq() {
  FREE_C_HEAP_ARRAY(double, _sequence);
}

void TruncatedSeq::add(double val) {
  AbsSeq::add(val);

  // get the oldest value in the sequence...
  double old_val = _sequence[_next];
  // ...remove it from the sum and sum of squares
  _sum -= old_val;
  _sum_of_squares -= old_val * old_val;

  // ...and update them with the new value
  _sum += val;
  _sum_of_squares += val * val;

  // now replace the old value with the new one
  _sequence[_next] = val;
  _next = (_next + 1) % _length;

  // only increase it if the buffer is not full
  if (_num < _length)
    ++_num;

  guarantee( variance() > -1.0, "variance should be >= 0" );
}

// can't easily keep track of this incrementally...
double TruncatedSeq::maximum() const {
  if (_num == 0)
    return 0.0;
  double ret = _sequence[0];
  for (int i = 1; i < _num; ++i) {
    double val = _sequence[i];
    if (val > ret)
      ret = val;
  }
  return ret;
}

double TruncatedSeq::last() const {
  if (_num == 0)
    return 0.0;
  unsigned last_index = (_next + _length - 1) % _length;
  return _sequence[last_index];
}

double TruncatedSeq::oldest() const {
  if (_num == 0)
    return 0.0;
  else if (_num < _length)
    // index 0 always oldest value until the array is full
    return _sequence[0];
  else {
    // since the array is full, _next is over the oldest value
    return _sequence[_next];
  }
}

double TruncatedSeq::predict_next() const {
  if (_num == 0)
    return 0.0;

  double num           = (double) _num;
  double x_squared_sum = 0.0;
  double x_sum         = 0.0;
  double y_sum         = 0.0;
  double xy_sum        = 0.0;
  double x_avg         = 0.0;
  double y_avg         = 0.0;

  int first = (_next + _length - _num) % _length;
  for (int i = 0; i < _num; ++i) {
    double x = (double) i;
    double y =  _sequence[(first + i) % _length];

    x_squared_sum += x * x;
    x_sum         += x;
    y_sum         += y;
    xy_sum        += x * y;
  }
  x_avg = x_sum / num;
  y_avg = y_sum / num;

  double Sxx = x_squared_sum - x_sum * x_sum / num;
  double Sxy = xy_sum - x_sum * y_sum / num;
  double b1 = Sxy / Sxx;
  double b0 = y_avg - b1 * x_avg;

  return b0 + b1 * num;
}


// Printing/Debugging Support

void AbsSeq::dump() { dump_on(tty); }

void AbsSeq::dump_on(outputStream* s) {
  s->print_cr("\t _num = %d, _sum = %7.3f, _sum_of_squares = %7.3f",
                  _num,      _sum,         _sum_of_squares);
  s->print_cr("\t _davg = %7.3f, _dvariance = %7.3f, _alpha = %7.3f",
                  _davg,         _dvariance,         _alpha);
}

void NumberSeq::dump_on(outputStream* s) {
  AbsSeq::dump_on(s);
  s->print_cr("\t\t _last = %7.3f, _maximum = %7.3f", _last, _maximum);
}

void TruncatedSeq::dump_on(outputStream* s) {
  AbsSeq::dump_on(s);
  s->print_cr("\t\t _length = %d, _next = %d", _length, _next);
  for (int i = 0; i < _length; i++) {
    if (i%5 == 0) {
      s->cr();
      s->print("\t");
    }
    s->print("\t[%d]=%7.3f", i, _sequence[i]);
  }
  s->cr();
}

HdrSeq::HdrSeq() {
  _hdr = NEW_C_HEAP_ARRAY(int*, MagBuckets, mtInternal);
  for (int c = 0; c < MagBuckets; c++) {
    _hdr[c] = NULL;
  }
}

HdrSeq::~HdrSeq() {
  for (int c = 0; c < MagBuckets; c++) {
    int* sub = _hdr[c];
    if (sub != NULL) {
      FREE_C_HEAP_ARRAY(int, sub);
    }
  }
  FREE_C_HEAP_ARRAY(int*, _hdr);
}

void HdrSeq::add(double val) {
  if (val < 0) {
    assert (false, "value (%8.2f) is not negative", val);
    val = 0;
  }

  NumberSeq::add(val);

  double v = val;
  int mag;
  if (v > 0) {
    mag = 0;
    while (v > 1) {
      mag++;
      v /= 10;
    }
    while (v < 0.1) {
      mag--;
      v *= 10;
    }
  } else {
    mag = MagMinimum;
  }

  int bucket = -MagMinimum + mag;
  int sub_bucket = (int) (v * ValBuckets);

  // Defensively saturate for product bits:
  if (bucket < 0) {
    assert (false, "bucket index (%d) underflow for value (%8.2f)", bucket, val);
    bucket = 0;
  }

  if (bucket >= MagBuckets) {
    assert (false, "bucket index (%d) overflow for value (%8.2f)", bucket, val);
    bucket = MagBuckets - 1;
  }

  if (sub_bucket < 0) {
    assert (false, "sub-bucket index (%d) underflow for value (%8.2f)", sub_bucket, val);
    sub_bucket = 0;
  }

  if (sub_bucket >= ValBuckets) {
    assert (false, "sub-bucket index (%d) overflow for value (%8.2f)", sub_bucket, val);
    sub_bucket = ValBuckets - 1;
  }

  int* b = _hdr[bucket];
  if (b == NULL) {
    b = NEW_C_HEAP_ARRAY(int, ValBuckets, mtInternal);
    for (int c = 0; c < ValBuckets; c++) {
      b[c] = 0;
    }
    _hdr[bucket] = b;
  }
  b[sub_bucket]++;
}

double HdrSeq::percentile(double level) const {
  // target should be non-zero to find the first sample
  int target = MAX2(1, (int) (level * num() / 100));
  int cnt = 0;
  for (int mag = 0; mag < MagBuckets; mag++) {
    if (_hdr[mag] != NULL) {
      for (int val = 0; val < ValBuckets; val++) {
        cnt += _hdr[mag][val];
        if (cnt >= target) {
          return pow(10.0, MagMinimum + mag) * val / ValBuckets;
        }
      }
    }
  }
  return maximum();
}

BinaryMagnitudeSeq::BinaryMagnitudeSeq() {
  _mags = NEW_C_HEAP_ARRAY(size_t, BitsPerSize_t, mtInternal);
  for (int c = 0; c < BitsPerSize_t; c++) {
    _mags[c] = 0;
  }
}

BinaryMagnitudeSeq::~BinaryMagnitudeSeq() {
  FREE_C_HEAP_ARRAY(size_t, _mags);
}

void BinaryMagnitudeSeq::add(size_t val) {
  int mag = log2_intptr(val) + 1;

  // Defensively saturate for product bits:
  if (mag < 0) {
    assert (false, "bucket index (%d) underflow for value (" SIZE_FORMAT ")", mag, val);
    mag = 0;
  }

  if (mag >= BitsPerSize_t) {
    assert (false, "bucket index (%d) overflow for value (" SIZE_FORMAT ")", mag, val);
    mag = BitsPerSize_t - 1;
  }

  Atomic::add((size_t)1, &_mags[mag]);
}

size_t BinaryMagnitudeSeq::level(int level) const {
  if (0 <= level && level < BitsPerSize_t) {
    return _mags[level];
  } else {
    return 0;
  }
}

size_t BinaryMagnitudeSeq::num() const {
  size_t r = 0;
  for (int c = 0; c < BitsPerSize_t; c++) {
    r += _mags[c];
  }
  return r;
}

int BinaryMagnitudeSeq::min_level() const {
  for (int c = 0; c < BitsPerSize_t; c++) {
    if (_mags[c] != 0) {
      return c;
    }
  }
  return BitsPerSize_t - 1;
}

int BinaryMagnitudeSeq::max_level() const {
  for (int c = BitsPerSize_t - 1; c > 0; c--) {
    if (_mags[c] != 0) {
      return c;
    }
  }
  return 0;
}


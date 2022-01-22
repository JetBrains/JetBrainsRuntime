/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2018, Google and/or its affiliates. All rights reserved.
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
 */

#include "ExceptionCheckingJniEnv.hpp"

namespace {

template<class T = void*>
class JNIVerifier {
 public:
  JNIVerifier(ExceptionCheckingJniEnv *env, const char* base_msg)
      : _env(env), _base_msg(base_msg), _return_error(NULL) {
  }

  ~JNIVerifier() {
    JNIEnv* jni_env = _env->GetJNIEnv();
    if (jni_env->ExceptionCheck()) {
      _env->HandleError(_base_msg);
      return;
    }

    if (_return_error != NULL) {
      ProcessReturnError();
    }
  }

  void ProcessReturnError() {
    int len = snprintf(NULL, 0, "%s : %s", _base_msg, _return_error) + 1;

    if (len <= 0) {
      _env->HandleError(_return_error);
      return;
    }

    char* full_message = (char*) malloc(len);
    if (full_message == NULL) {
      _env->HandleError(_return_error);
      return;
    }

    snprintf(full_message, len, "%s : %s", _base_msg, _return_error);

    _env->HandleError(full_message);
    free(full_message);
  }

  T ResultNotNull(T ptr) {
    if (ptr == NULL) {
      _return_error = "Return is NULL";
    }
    return ptr;
  }

 private:
  ExceptionCheckingJniEnv* _env;
  const char* const _base_msg;
  const char* _return_error;
};

}

jclass ExceptionCheckingJniEnv::GetObjectClass(jobject obj) {
  JNIVerifier<jclass> marker(this, "GetObjectClass");
  return marker.ResultNotNull(_jni_env->GetObjectClass(obj));
}

jfieldID ExceptionCheckingJniEnv::GetFieldID(jclass klass, const char *name, const char* type) {
  JNIVerifier<jfieldID> marker(this, "GetObjectClass");
  return marker.ResultNotNull(_jni_env->GetFieldID(klass, name, type));
}

jobject ExceptionCheckingJniEnv::GetObjectField(jobject obj, jfieldID field) {
  JNIVerifier<jobject> marker(this, "GetObjectField");
  return marker.ResultNotNull(_jni_env->GetObjectField(obj, field));
}

void ExceptionCheckingJniEnv::SetObjectField(jobject obj, jfieldID field, jobject value) {
  JNIVerifier<> marker(this, "SetObjectField");
  _jni_env->SetObjectField(obj, field, value);
}

jobject ExceptionCheckingJniEnv::NewGlobalRef(jobject obj) {
  JNIVerifier<jobject> marker(this, "GetObjectField");
  return marker.ResultNotNull(_jni_env->NewGlobalRef(obj));
}

void ExceptionCheckingJniEnv::DeleteGlobalRef(jobject obj) {
  JNIVerifier<> marker(this, "DeleteGlobalRef");
  _jni_env->DeleteGlobalRef(obj);
}

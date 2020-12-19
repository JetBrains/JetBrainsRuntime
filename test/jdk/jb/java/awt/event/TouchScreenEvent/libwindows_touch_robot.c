/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

#include <jni.h>

#include <windows.h>
#include <WinUser.h>

POINTER_TOUCH_INFO create_touch_info()
{
  POINTER_TOUCH_INFO contact;
  InitializeTouchInjection(1, TOUCH_FEEDBACK_DEFAULT); // Number of contact point
  memset(&contact, 0, sizeof(POINTER_TOUCH_INFO));

  contact.touchFlags = TOUCH_FLAG_NONE;
  contact.touchMask = TOUCH_MASK_CONTACTAREA | TOUCH_MASK_ORIENTATION | TOUCH_MASK_PRESSURE;
  contact.orientation = 90; // Orientation of 90 means touching perpendicular to screen.
  contact.pressure = 32000;

  return contact;
}

void set_contact_area(POINTER_TOUCH_INFO *contact)
{
  // 4 x 4 pixel contact area
  contact->rcContact.top = contact->pointerInfo.ptPixelLocation.y - 2;
  contact->rcContact.bottom = contact->pointerInfo.ptPixelLocation.y + 2;
  contact->rcContact.left = contact->pointerInfo.ptPixelLocation.x - 2;
  contact->rcContact.right = contact->pointerInfo.ptPixelLocation.x + 2;
}

void touchBegin(POINTER_TOUCH_INFO* contact, LONG x, LONG y)
{
  contact->pointerInfo.pointerType = PT_TOUCH;
  // primary finger pointerId == 0
  contact->pointerInfo.pointerId = 0;
  contact->pointerInfo.ptPixelLocation.x = x;
  contact->pointerInfo.ptPixelLocation.y = y;
  set_contact_area(contact);

  contact->pointerInfo.pointerFlags = POINTER_FLAG_DOWN | POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
  InjectTouchInput(1, contact);
}

void touchUpdate(POINTER_TOUCH_INFO* contact, LONG x, LONG y)
{
  contact->pointerInfo.pointerFlags = POINTER_FLAG_UPDATE | POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
  contact->pointerInfo.ptPixelLocation.x = x;
  contact->pointerInfo.ptPixelLocation.y = y;
  InjectTouchInput(1, contact);
}

void touchEnd(POINTER_TOUCH_INFO* contact)
{
  contact->pointerInfo.pointerFlags = POINTER_FLAG_UP;
  InjectTouchInput(1, contact);
}

/*
 * Class:     quality_util_WindowsTouchRobot
 * Method:    clickImpl
 * Signature: (II)V
 */
JNIEXPORT void JNICALL
Java_WindowsTouchRobot_clickImpl(JNIEnv* env, jobject o,
                                              jint x, jint y)
{
  POINTER_TOUCH_INFO contact = create_touch_info();
  touchBegin(&contact, x, y);
  touchEnd(&contact);
}

/*
 * Class:     quality_util_WindowsTouchRobot
 * Method:    moveImpl
 * Signature: (IIII)V
 */
JNIEXPORT void JNICALL
Java_WindowsTouchRobot_moveImpl(JNIEnv* env, jobject o,
                                             jint fromX, jint fromY,
                                             jint toX, jint toY)
{
  POINTER_TOUCH_INFO contact = create_touch_info();
  touchBegin(&contact, fromX, fromY);
  touchUpdate(&contact, toX, toY);
  touchEnd(&contact);
}
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
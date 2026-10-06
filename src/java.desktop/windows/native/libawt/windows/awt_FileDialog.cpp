/*
 * Copyright (c) 1996, 2020, Oracle and/or its affiliates. All rights reserved.
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

#include "awt_ole.h"
#include "awt.h"
#include "awt_FileDialog.h"
#include "awt_Dialog.h"
#include "awt_Toolkit.h"
#include "ComCtl32Util.h"
#include "com_jetbrains_desktop_JBRFileDialog.h"
#include <commdlg.h>
#include <cderr.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <shobjidl.h>

/************************************************************************
 * AwtFileDialog fields
 */

/* WFileDialogPeer ids */
jfieldID AwtFileDialog::parentID;
jfieldID AwtFileDialog::fileFilterID;
jmethodID AwtFileDialog::setHWndMID;
jmethodID AwtFileDialog::handleSelectedMID;
jmethodID AwtFileDialog::handleCancelMID;
jmethodID AwtFileDialog::checkFilenameFilterMID;
jmethodID AwtFileDialog::isMultipleModeMID;

/* FileDialog ids */
jfieldID AwtFileDialog::modeID;
jfieldID AwtFileDialog::dirID;
jfieldID AwtFileDialog::fileID;
jfieldID AwtFileDialog::filterID;
jfieldID AwtFileDialog::jbrDialogID;
jfieldID AwtFileDialog::openButtonTextID;
jfieldID AwtFileDialog::selectFolderButtonTextID;
jfieldID AwtFileDialog::allFilesFilterDescriptionID;
jfieldID AwtFileDialog::fileFilterDescriptionID;
jfieldID AwtFileDialog::fileFilterExtensionsID;
jfieldID AwtFileDialog::hintsID;

class CoTaskStringHolder {
public:
    CoTaskStringHolder() : m_str(NULL) {}

    CoTaskStringHolder(CoTaskStringHolder& other) {
        m_str = other.m_str;
        other.m_str = NULL;
    }

    CoTaskStringHolder& operator=(CoTaskStringHolder& other) {
        if (m_str == other.m_str) return *this;
        Clean();
        m_str = other.m_str;
        other.m_str = NULL;
        return *this;
    }

    LPTSTR* operator&() {
        return &m_str;
    }

    operator bool() {
        return m_str != NULL;
    }

    operator LPTSTR() {
        return m_str;
    }

    ~CoTaskStringHolder() {
        Clean();
    }
private:
    LPTSTR m_str;

    void Clean() {
        if (m_str) {
            ::CoTaskMemFree(m_str);
            m_str = NULL;
        }
    }
};

template <typename T>
class SmartHolderBase {
public:
    SmartHolderBase() : m_pointer(NULL) {}

    SmartHolderBase& operator=(const SmartHolderBase&) = delete;

    void Attach(T* other) {
        if (m_pointer == other) return;
        Clean();
        m_pointer = other;
    }

    operator bool() {
        return m_pointer != NULL;
    }

    operator T*() {
        return m_pointer;
    }

    ~SmartHolderBase() {
        Clean();
    }
protected:
    T* m_pointer;

    virtual void Clean() {
        if (m_pointer) {
            delete m_pointer;
            m_pointer = NULL;
        }
    }
};

template<typename T>
class SmartHolder : public SmartHolderBase<T> {
};

template <typename T>
class SmartHolder<T[]> : public SmartHolderBase<T> {
    virtual void Clean() {
        if (this->m_pointer) {
            delete [] this->m_pointer;
            this->m_pointer = NULL;
        }
    }
};

/* Localized filter string */
#define MAX_FILTER_STRING       128
static TCHAR s_fileFilterString[MAX_FILTER_STRING];
/* Non-localized suffix of the filter string */
static const TCHAR s_additionalString[] = TEXT(" (*.*)\0*.*\0");

// Default limit of the output buffer.
#define SINGLE_MODE_BUFFER_LIMIT     MAX_PATH+1
#define MULTIPLE_MODE_BUFFER_LIMIT   32768

// The name of the property holding the pointer to the OPENFILENAME structure.
static LPCTSTR OpenFileNameProp = TEXT("AWT_OFN");

_COM_SMARTPTR_TYPEDEF(IFileDialog, __uuidof(IFileDialog));
_COM_SMARTPTR_TYPEDEF(IFileDialogEvents, __uuidof(IFileDialogEvents));
_COM_SMARTPTR_TYPEDEF(IShellItem, __uuidof(IShellItem));
_COM_SMARTPTR_TYPEDEF(IFileOpenDialog, __uuidof(IFileOpenDialog));
_COM_SMARTPTR_TYPEDEF(IShellItemArray, __uuidof(IShellItemArray));
_COM_SMARTPTR_TYPEDEF(IOleWindowPtr, __uuidof(IOleWindowPtr));

/***********************************************************************/

void
AwtFileDialog::Initialize(JNIEnv *env, jstring filterDescription)
{
    int length = env->GetStringLength(filterDescription);
    DASSERT(length + 1 < MAX_FILTER_STRING);
    LPCTSTR tmp = JNU_GetStringPlatformChars(env, filterDescription, NULL);
    _tcscpy_s(s_fileFilterString, MAX_FILTER_STRING, tmp);
    JNU_ReleaseStringPlatformChars(env, filterDescription, tmp);

    //AdditionalString should be terminated by two NULL characters (Windows
    //requirement), so we have to organize the following cycle and use memcpy
    //unstead of, for example, strcat.
    LPTSTR s = s_fileFilterString;
    while (*s) {
        ++s;
        DASSERT(s < s_fileFilterString + MAX_FILTER_STRING);
    }
    DASSERT(s + sizeof(s_additionalString) < s_fileFilterString + MAX_FILTER_STRING);
    memcpy(s, s_additionalString, sizeof(s_additionalString));
}

LRESULT CALLBACK FileDialogWndProc(HWND hWnd, UINT message,
                                        WPARAM wParam, LPARAM lParam)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    switch (message) {
        case WM_COMMAND: {
            if (LOWORD(wParam) == IDCANCEL)
            {
                // Unlike Print/Page dialogs, we only handle IDCANCEL here and
                // don't handle IDOK. This is because user can press OK button
                // when no file is selected, and the dialog is not closed. So
                // OK button is handled in the CDN_FILEOK notification handler
                // (see FileDialogHookProc below)
                jobject peer = (jobject)(::GetProp(hWnd, ModalDialogPeerProp));
                env->CallVoidMethod(peer, AwtFileDialog::setHWndMID, (jlong)0);
            }
            break;
        }
        case WM_SETICON: {
            return 0;
        }
    }

    WNDPROC lpfnWndProc = (WNDPROC)(::GetProp(hWnd, NativeDialogWndProcProp));
    return ComCtl32Util::GetInstance().DefWindowProc(lpfnWndProc, hWnd, message, wParam, lParam);
}

static UINT_PTR CALLBACK
FileDialogHookProc(HWND hdlg, UINT uiMsg, WPARAM wParam, LPARAM lParam)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    TRY;

    HWND parent = ::GetParent(hdlg);

    switch(uiMsg) {
        case WM_INITDIALOG: {
            OPENFILENAME *ofn = (OPENFILENAME *)lParam;
            jobject peer = (jobject)(ofn->lCustData);
            env->CallVoidMethod(peer, AwtFileDialog::setHWndMID,
                                (jlong)parent);
            ::SetProp(parent, ModalDialogPeerProp, reinterpret_cast<HANDLE>(peer));

            // fix for 4508670 - disable CS_SAVEBITS
            DWORD style = ::GetClassLong(hdlg,GCL_STYLE);
            ::SetClassLong(hdlg,GCL_STYLE,style & ~CS_SAVEBITS);

            // set appropriate icon for parentless dialogs
            jobject awtParent = env->GetObjectField(peer, AwtFileDialog::parentID);
            if (awtParent == NULL) {
                ::SendMessage(parent, WM_SETICON, (WPARAM)ICON_BIG,
                              (LPARAM)AwtToolkit::GetInstance().GetAwtIcon());
            } else {
                AwtWindow *awtWindow = (AwtWindow *)JNI_GET_PDATA(awtParent);
                ::SendMessage(parent, WM_SETICON, (WPARAM)ICON_BIG,
                                               (LPARAM)(awtWindow->GetHIcon()));
                ::SendMessage(parent, WM_SETICON, (WPARAM)ICON_SMALL,
                                             (LPARAM)(awtWindow->GetHIconSm()));
                env->DeleteLocalRef(awtParent);
            }

            // subclass dialog's parent to receive additional messages
            WNDPROC lpfnWndProc = ComCtl32Util::GetInstance().SubclassHWND(parent,
                                                                           FileDialogWndProc);
            ::SetProp(parent, NativeDialogWndProcProp, reinterpret_cast<HANDLE>(lpfnWndProc));

            ::SetProp(parent, OpenFileNameProp, (void *)lParam);

            break;
        }
        case WM_DESTROY: {
            HIMC hIMC = ::ImmGetContext(hdlg);
            if (hIMC != NULL) {
                ::ImmNotifyIME(hIMC, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
                ::ImmReleaseContext(hdlg, hIMC);
            }

            WNDPROC lpfnWndProc = (WNDPROC)(::GetProp(parent, NativeDialogWndProcProp));
            ComCtl32Util::GetInstance().UnsubclassHWND(parent,
                                                       FileDialogWndProc,
                                                       lpfnWndProc);
            ::RemoveProp(parent, ModalDialogPeerProp);
            ::RemoveProp(parent, NativeDialogWndProcProp);
            ::RemoveProp(parent, OpenFileNameProp);
            break;
        }
        case WM_NOTIFY: {
            OFNOTIFYEX *notifyEx = (OFNOTIFYEX *)lParam;
            if (notifyEx) {
                jobject peer = (jobject)(::GetProp(parent, ModalDialogPeerProp));
                if (notifyEx->hdr.code == CDN_INCLUDEITEM) {
                    LPITEMIDLIST pidl = (LPITEMIDLIST)notifyEx->pidl;
                    // Get the filename and directory
                    TCHAR szPath[MAX_PATH];
                    if (!::SHGetPathFromIDList(pidl, szPath)) {
                        return TRUE;
                    }
                    jstring strPath = JNU_NewStringPlatform(env, szPath);
                    if (strPath == NULL) {
                        throw std::bad_alloc();
                    }
                    // Call FilenameFilter.accept with path and filename
                    UINT uRes = (env->CallBooleanMethod(peer,
                        AwtFileDialog::checkFilenameFilterMID, strPath) == JNI_TRUE);
                    env->DeleteLocalRef(strPath);
                    return uRes;
                } else if (notifyEx->hdr.code == CDN_FILEOK) {
                    // This notification is sent when user selects some file and presses
                    // OK button; it is not sent when no file is selected. So it's time
                    // to unblock all the windows blocked by this dialog as it will
                    // be closed soon
                    env->CallVoidMethod(peer, AwtFileDialog::setHWndMID, (jlong)0);
                } else if (notifyEx->hdr.code == CDN_SELCHANGE) {
                    // reallocate the buffer if the buffer is too small
                    LPOPENFILENAME lpofn = (LPOPENFILENAME)GetProp(parent, OpenFileNameProp);

                    UINT nLength = CommDlg_OpenSave_GetSpec(parent, NULL, 0) +
                                   CommDlg_OpenSave_GetFolderPath(parent, NULL, 0);

                    if (lpofn->nMaxFile < nLength)
                    {
                        // allocate new buffer
                        LPTSTR newBuffer = new TCHAR[nLength];

                        if (newBuffer) {
                            memset(newBuffer, 0, nLength * sizeof(TCHAR));
                            LPTSTR oldBuffer = lpofn->lpstrFile;
                            lpofn->lpstrFile = newBuffer;
                            lpofn->nMaxFile = nLength;
                            // free the previously allocated buffer
                            if (oldBuffer) {
                                delete[] oldBuffer;
                            }

                        }
                    }
                }
            }
            break;
        }
    }

    return FALSE;

    CATCH_BAD_ALLOC_RET(TRUE);
}

struct FileDialogData {
    IFileDialogPtr fileDialog;
    SmartHolder<TCHAR[]> result;
    UINT resultSize;
    jobject peer;
    BOOL ignoreCustomizations;

    // Whether the container folder of the current item should be used irregardless of the dialog result.
    BOOL forceUseContainerFolder;

    SmartHolder<WCHAR[]> openButtonText;
    SmartHolder<WCHAR[]> selectFolderButtonText;

    // Last observed value of the "File name" text box before user entered or selected a directory; kept for checking in
    // some of the routines which have to behave differently in case user never touched the file name box.
    CoTaskStringHolder lastIgnoredFileName;
};

bool SkipSelectedResults(FileDialogData *data) {
    // Usually, when the "Open" / "Select Folder" button is pressed, we should close the file dialog (sometimes do it
    // forcibly, because IFileDialog doesn't always thinks it should accept currently selected items, even if the dialog
    // logic advices it should).
    //
    // But there's one case when we shouldn't: when the user has pasted a directory path into the "File name" text box,
    // we should navigate to the directory entered instead of closing the dialog. This check guarantees we won't close
    // the dialog if a directory path is pasted.
    OLE_TRY
        IFileDialogPtr fileDialog = data->fileDialog;

        CoTaskStringHolder currentFileName;
        OLE_HRT(fileDialog->GetFileName(&currentFileName));
        if (_tcslen(currentFileName) == 0 || wcscmp(currentFileName, data->lastIgnoredFileName) == 0) {
            return false; // do not skip anything if there's no file name entered
        }

        IFileOpenDialogPtr fileOpenDialog = nullptr;
        IShellItemArrayPtr psia = nullptr;
        OLE_HRT(fileDialog->QueryInterface(IID_PPV_ARGS(&fileOpenDialog)))
        if (FAILED(fileOpenDialog->GetSelectedItems(&psia))) {
            return false; // not a real path entered (thus it couldn't be resolved): don't skip anything
        }

        DWORD itemsCount = 0;
        OLE_HRT(psia->GetCount(&itemsCount));
        if (itemsCount != 1) {
            return false; // zero or multiple items selected for some reason: fall back to default flow
        }

        IShellItemPtr singleItem = nullptr;
        OLE_HRT(psia->GetItemAt(0, &singleItem));

        SFGAOF attributes = 0;
        bool isFolder = singleItem->GetAttributes(SFGAO_FOLDER, &attributes) == S_OK;
        if (isFolder) {
            // There's a folder name or path written in the file name text box: skip result selection, then.
            return true;
        }
    OLE_CATCH

    // Skip selected results (and fall back to default dialog logic) in case of any error.
    return true;
}

HRESULT GetSelectedResults(FileDialogData *data) {
    OLE_TRY

    IFileOpenDialogPtr fileOpenDialog;
    size_t currentOffset = 0;
    IShellItemArrayPtr psia;
    DWORD itemsCount;

    OLE_HRT(data->fileDialog->QueryInterface(IID_PPV_ARGS(&fileOpenDialog)))
    OLE_HRT(fileOpenDialog->GetSelectedItems(&psia));
    OLE_HRT(psia->GetCount(&itemsCount));

    UINT maxBufferSize = (MAX_PATH + 1) * itemsCount + 1;
    data->result.Attach(new TCHAR[maxBufferSize]);
    data->resultSize = maxBufferSize;
    LPTSTR resultBuffer = data->result;
    for (DWORD i = 0; i < itemsCount; i++) {
        IShellItemPtr psi;
        OLE_HRT(psia->GetItemAt(i, &psi));
        if (i == 0 && itemsCount > 1) {
            IShellItemPtr psiParent;
            CoTaskStringHolder filePath;
            OLE_HRT(psi->GetParent(&psiParent));
            OLE_HRT(psiParent->GetDisplayName(SIGDN_FILESYSPATH, &filePath));
            size_t filePathLength = _tcslen(filePath);
            _tcsncpy(resultBuffer + currentOffset, filePath, filePathLength);
            resultBuffer[currentOffset + filePathLength] = _T('\0');
            currentOffset += filePathLength + 1;
        }

        CoTaskStringHolder filePath;
        SIGDN displayForm = itemsCount > 1 ? SIGDN_PARENTRELATIVE : SIGDN_FILESYSPATH;
        OLE_HRT(psi->GetDisplayName(displayForm, &filePath));
        size_t filePathLength = _tcslen(filePath);
        _tcsncpy(resultBuffer + currentOffset, filePath, filePathLength);
        resultBuffer[currentOffset + filePathLength] = _T('\0');
        currentOffset += filePathLength + 1;
    }
    resultBuffer[currentOffset] = _T('\0');
    resultBuffer[currentOffset + 1] = _T('\0');
    data->fileDialog->Close(S_OK);

    OLE_CATCH
    OLE_RETURN_HR
}

bool ShouldForceCloseDialogOnApply(FileDialogData *data) {
    // We shouldn't force-close the dialog if we aren't in the force-close mode, or if we're in the folder picker mode:
    if (!data->forceUseContainerFolder) return false;
    if (data->ignoreCustomizations) return false;

    IFileDialogPtr fileDialog = data->fileDialog;

    OLE_TRY
        // We should force-close the dialog on Enter, if there's nothing entered into the file name box:
        CoTaskStringHolder currentFileName;
        OLE_HRT(fileDialog->GetFileName(&currentFileName));
        if (_tcslen(currentFileName) == 0 || wcscmp(currentFileName, data->lastIgnoredFileName) == 0) {
            return true;
        }

        // If there's something entered into the file name box, then we should only force-close the dialog if it
        // corresponds to a real file; otherwise, the user was probably looking for a file filter (e.g. entered
        // "*.xml"), so we shouldn't close the dialog.
        //
        // To detect it, check the selected item list: if it's unavailable, it means that the item entered isn't an
        // existing one.
        IFileOpenDialogPtr fileOpenDialog;
        IShellItemArrayPtr psia;
        OLE_HRT(fileDialog->QueryInterface(IID_PPV_ARGS(&fileOpenDialog)))
        if (FAILED(fileOpenDialog->GetSelectedItems(&psia))) {
            return false;
        }
    OLE_CATCH

    // Don't close the dialog: this will assume default behavior, as if no customization were applied.
    return false;
}

LRESULT CALLBACK
FileDialogSubclassProc(HWND hWnd, UINT uMsg, WPARAM wParam, LPARAM lParam, UINT_PTR uIdSubclass, DWORD_PTR dwRefData)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    TRY;

    HWND parent = ::GetParent(hWnd);

    switch (uMsg) {
        case WM_COMMAND: {
            if (HIWORD(wParam) == BN_CLICKED && LOWORD(wParam) == IDOK) {
                FileDialogData *data = (FileDialogData*) dwRefData;
                if (!data->ignoreCustomizations) {
                    OLE_TRY
                        if (!SkipSelectedResults(data)) {
                            OLE_HRT(GetSelectedResults(data));
                        }
                    OLE_CATCH

                    // Force to close the dialog even if there's no item selected, but we're in the forceUseContainerFolder
                    // mode.
                    if (ShouldForceCloseDialogOnApply(data)) {
                        DestroyWindow(hWnd);
                    }
                }
            }
            if (LOWORD(wParam) == IDCANCEL) {
                jobject peer = (jobject) (::GetProp(hWnd, ModalDialogPeerProp));
                env->CallVoidMethod(peer, AwtFileDialog::setHWndMID, (jlong) 0);

                FileDialogData *data = (FileDialogData*) dwRefData;
                if (!data->ignoreCustomizations) {
                    data->forceUseContainerFolder = FALSE; // disable mode on cancel
                }
            }
            break;
        }
        case WM_SETICON: {
            return 0;
        }
        case WM_DESTROY: {
            HIMC hIMC = ::ImmGetContext(hWnd);
            if (hIMC != NULL) {
                ::ImmNotifyIME(hIMC, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
                ::ImmReleaseContext(hWnd, hIMC);
            }

            RemoveWindowSubclass(hWnd, &FileDialogSubclassProc, uIdSubclass);

            ::RemoveProp(parent, ModalDialogPeerProp);
            break;
        }
    }

    return DefSubclassProc(hWnd, uMsg, wParam, lParam);

    CATCH_BAD_ALLOC_RET(TRUE);
}

class CDialogEventHandler : public IFileDialogEvents
{
public:
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv)
    {
        static const QITAB qit[] = {
                QITABENT(CDialogEventHandler, IFileDialogEvents),
                { 0 },
        };
        return QISearch(this, qit, riid, ppv);
    }

    IFACEMETHODIMP_(ULONG) AddRef()
    {
        return InterlockedIncrement(&m_refCount);
    }

    IFACEMETHODIMP_(ULONG) Release()
    {
        long retVal = InterlockedDecrement(&m_refCount);
        if (!retVal)
            delete this;
        return retVal;
    }

    IFACEMETHODIMP OnFolderChange(IFileDialog *fileDialog) {
        if (!m_activated) {
            InitDialog(fileDialog);
            m_activated = true;
        }

        OLE_TRY
            if (!data->ignoreCustomizations) {
                IShellItemPtr folder = nullptr;
                OLE_HRT(fileDialog->GetFolder(&folder));
                CoTaskStringHolder filePath;
                OLE_HRT(folder->GetDisplayName(SIGDN_FILESYSPATH, &filePath));
                size_t filePathLength = _tcslen(filePath);
                data->result.Attach(new TCHAR[filePathLength + 1]);
                _tcscpy_s(data->result, filePathLength + 1, filePath);
                data->resultSize = (UINT)(filePathLength + 1);

                CoTaskStringHolder fileName;
                OLE_HRT(fileDialog->GetFileName(&fileName));
                data->lastIgnoredFileName = fileName;

                OLE_HRT(fileDialog->SetOkButtonLabel(data->selectFolderButtonText));
                data->forceUseContainerFolder = TRUE;
            }
        OLE_CATCH

        return S_OK;
    };

    IFACEMETHODIMP OnFileOk(IFileDialog *) {
        OLE_TRY
            OLE_HRT(GetSelectedResults(data));
        OLE_CATCH

        // Since the user has chosen a file, reset the forceUseContainerFolder mode.
        data->forceUseContainerFolder = FALSE;

        return S_OK;
    };

    IFACEMETHODIMP OnFolderChanging(IFileDialog *, IShellItem *) { return S_OK; };
    IFACEMETHODIMP OnHelp(IFileDialog *) { return S_OK; };
    IFACEMETHODIMP OnSelectionChange(IFileDialog *fileDialog) {
        OLE_TRY
            if (!data->ignoreCustomizations) {
                IShellItemPtr currentSelection = NULL;
                OLE_HRT(fileDialog->GetCurrentSelection(&currentSelection));
                SFGAOF att = 0;
                bool isFolder = currentSelection->GetAttributes(SFGAO_FOLDER, &att) == S_OK;

                LPCWSTR buttonLabel = nullptr;
                if (isFolder) {
                    buttonLabel = data->selectFolderButtonText ? (WCHAR*) data->selectFolderButtonText : L"Select Folder";
                } else {
                    buttonLabel = data->openButtonText ? (WCHAR*) data->openButtonText : L"Open";
                }
                OLE_HRT(fileDialog->SetOkButtonLabel(buttonLabel));

                // Since we have an item selected, we're no more in the forceUseContainerFolder mode.
                data->forceUseContainerFolder = FALSE;

                if (isFolder) {
                    CoTaskStringHolder fileName;
                    OLE_HRT(fileDialog->GetFileName(&fileName));
                    data->lastIgnoredFileName = fileName;
                }
            }
        OLE_CATCH

        return S_OK;
    };
    IFACEMETHODIMP OnShareViolation(IFileDialog *, IShellItem *, FDE_SHAREVIOLATION_RESPONSE *) { return S_OK; };
    IFACEMETHODIMP OnTypeChange(IFileDialog *pfd) { return S_OK; };
    IFACEMETHODIMP OnOverwrite(IFileDialog *, IShellItem *, FDE_OVERWRITE_RESPONSE *) { return S_OK; };

    CDialogEventHandler(FileDialogData *data) : data(data), m_refCount(1), m_activated(false) { };
private:
    ~CDialogEventHandler() { };
    FileDialogData *data;
    bool m_activated;
    long m_refCount;

    void InitDialog(IFileDialog *fileDialog) {
        JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

        TRY;
        OLE_TRY

        IOleWindowPtr pWindow;
        OLE_HR = fileDialog->QueryInterface(IID_PPV_ARGS(&pWindow));
        if (!SUCCEEDED(OLE_HR))
            return;

        HWND hdlg;
        OLE_HRT(pWindow->GetWindow(&hdlg));

        jobject peer = data->peer;
        env->CallVoidMethod(peer, AwtFileDialog::setHWndMID, (jlong)hdlg);
        ::SetProp(hdlg, ModalDialogPeerProp, reinterpret_cast<HANDLE>(peer));

        // fix for 4508670 - disable CS_SAVEBITS
        DWORD style = ::GetClassLong(hdlg, GCL_STYLE);
        ::SetClassLong(hdlg, GCL_STYLE, style & ~CS_SAVEBITS);

        // set appropriate icon for parentless dialogs
        jobject awtParent = env->GetObjectField(peer, AwtFileDialog::parentID);
        if (awtParent == NULL) {
            ::SendMessage(hdlg, WM_SETICON, (WPARAM)ICON_BIG,
                          (LPARAM)AwtToolkit::GetInstance().GetAwtIcon());
        } else {
            AwtWindow *awtWindow = (AwtWindow *)JNI_GET_PDATA(awtParent);
            ::SendMessage(hdlg, WM_SETICON, (WPARAM)ICON_BIG,
                          (LPARAM)(awtWindow->GetHIcon()));
            ::SendMessage(hdlg, WM_SETICON, (WPARAM)ICON_SMALL,
                          (LPARAM)(awtWindow->GetHIconSm()));
            env->DeleteLocalRef(awtParent);
        }

        SetWindowSubclass(hdlg, &FileDialogSubclassProc, 0, (DWORD_PTR) data);

        OLE_CATCH
        CATCH_BAD_ALLOC;
    }
};

HRESULT CDialogEventHandler_CreateInstance(FileDialogData *data, REFIID riid, void **ppv)
{
    OLE_TRY
    IFileDialogEventsPtr dlg(new CDialogEventHandler(data), false);
    OLE_HRT(dlg->QueryInterface(riid, ppv));
    OLE_CATCH
    OLE_RETURN_HR
}

HRESULT CreateShellItem(LPTSTR path, IShellItemPtr& shellItem) {
    size_t pathLength = _tcslen(path);
    for (size_t index = 0; index < pathLength; index++) {
        if (path[index] == _T('/'))
            path[index] = _T('\\');
    }

    return ::SHCreateItemInKnownFolder(FOLDERID_ComputerFolder, 0, path, IID_PPV_ARGS(&shellItem));
}

void AttachString(JNIEnv *env, const jstring string, SmartHolder<WCHAR[]> &holder) {
    if (JNU_IsNull(env, string)) {
        holder.Attach(nullptr);
    } else {
        JavaStringBuffer buffer(env, string);

        size_t length = buffer.GetSize();
        size_t lengthWithTerminatingNull = length + 1;
        holder.Attach(new WCHAR[lengthWithTerminatingNull]);

        wcscpy_s(holder, lengthWithTerminatingNull, buffer);
    }
}

void SaveCommonDialogLocalizationData(JNIEnv *env, const jobject jbrFileDialog, FileDialogData &data) {
    jstring openButtonText = static_cast<jstring>(env->GetObjectField(jbrFileDialog, AwtFileDialog::openButtonTextID));
    jstring selectFolderButtonText = static_cast<jstring>(env->GetObjectField(jbrFileDialog, AwtFileDialog::selectFolderButtonTextID));

    AttachString(env, openButtonText, data.openButtonText);
    AttachString(env, selectFolderButtonText, data.selectFolderButtonText);

    env->DeleteLocalRef(openButtonText);
    env->DeleteLocalRef(selectFolderButtonText);
}

void
AwtFileDialog::Show(void *p)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    jobject peer;
    LPTSTR currentDirectory = NULL;
    jint mode = 0;
    BOOL result = FALSE;
    DWORD dlgerr;
    jstring directory = NULL;
    jstring title = NULL;
    jstring file = NULL;
    jobject fileFilter = NULL;
    jobject target = NULL;
    jobject parent = NULL;
    AwtComponent* awtParent = NULL;
    jboolean multipleMode = JNI_FALSE;
    wchar_t *allFilesFilterDescrBuf = NULL;
    wchar_t *fileFilterDescrBuf = NULL;
    wchar_t *fileFilterSpecBuf = NULL;

    OLE_DECL
    OLEHolder _ole_;
    IFileDialogPtr pfd;
    IFileDialogEventsPtr pfde;
    IShellItemPtr psiResult;
    FileDialogData data;
    DWORD dwCookie = OLE_BAD_COOKIE;

    OPENFILENAME ofn;
    memset(&ofn, 0, sizeof(ofn));

    peer = (jobject)p;

    LPTSTR fileBuffer = NULL;

    BOOL useCommonItemDialog = JNU_CallStaticMethodByName(env, NULL,
            "sun/awt/windows/WFileDialogPeer", "useCommonItemDialog", "()Z").z == JNI_TRUE;
    try {
        DASSERT(peer);

        target = env->GetObjectField(peer, AwtObject::targetID);
        parent = env->GetObjectField(peer, AwtFileDialog::parentID);
        if (parent != NULL) {
            awtParent = (AwtComponent *)JNI_GET_PDATA(parent);
        }
//      DASSERT(awtParent);
        title = (jstring)(env)->GetObjectField(target, AwtDialog::titleID);
        /*
              Fix for 6488834.
              To disable Win32 native parent modality we have to set
              hwndOwner field to either NULL or some hidden window. For
              parentless dialogs we use NULL to show them in the taskbar,
              and for all other dialogs AwtToolkit's HWND is used.
            */
        /* [moklev] This fix does not needed anymore
         * Tested on Windows 10 with example from JDK-4080029
         * Revert the fix and set the proper parent to keep correct position of modal dialogs
         */
//        HWND hwndOwner = awtParent ? AwtToolkit::GetInstance().GetHWnd() : NULL;
        HWND hwndOwner = awtParent ? awtParent->GetHWnd() : NULL;

        if (title == NULL || env->GetStringLength(title)==0) {
            title = JNU_NewStringPlatform(env, L" ");
            if (title == NULL) {
                throw std::bad_alloc();
            }
        }

        JavaStringBuffer titleBuffer(env, title);
        directory =
            (jstring)env->GetObjectField(target, AwtFileDialog::dirID);
        JavaStringBuffer directoryBuffer(env, directory);

        multipleMode = env->CallBooleanMethod(peer, AwtFileDialog::isMultipleModeMID);

        UINT bufferLimit;
        if (multipleMode == JNI_TRUE) {
            bufferLimit = MULTIPLE_MODE_BUFFER_LIMIT;
        } else {
            bufferLimit = SINGLE_MODE_BUFFER_LIMIT;
        }
        fileBuffer = new TCHAR[bufferLimit];
        memset(fileBuffer, 0, bufferLimit * sizeof(TCHAR));

        file = (jstring)env->GetObjectField(target, AwtFileDialog::fileID);
        if (file != NULL) {
            LPCTSTR tmp = JNU_GetStringPlatformChars(env, file, NULL);
            _tcsncpy(fileBuffer, tmp, bufferLimit - 2); // the fileBuffer is double null terminated string
            JNU_ReleaseStringPlatformChars(env, file, tmp);
        } else {
            fileBuffer[0] = _T('\0');
        }

        fileFilter = env->GetObjectField(peer, AwtFileDialog::fileFilterID);

        if (!useCommonItemDialog) {
            ofn.lStructSize = sizeof(ofn);
            ofn.lpstrFilter = s_fileFilterString;
            ofn.nFilterIndex = 1;
            /*
              Fix for 6488834.
              To disable Win32 native parent modality we have to set
              hwndOwner field to either NULL or some hidden window. For
              parentless dialogs we use NULL to show them in the taskbar,
              and for all other dialogs AwtToolkit's HWND is used.
            */
            if (awtParent != NULL) {
                ofn.hwndOwner = AwtToolkit::GetInstance().GetHWnd();
            } else {
                ofn.hwndOwner = NULL;
            }
            ofn.lpstrFile = fileBuffer;
            ofn.nMaxFile = bufferLimit;
            ofn.lpstrTitle = titleBuffer;
            ofn.lpstrInitialDir = directoryBuffer;
            ofn.Flags = OFN_LONGNAMES | OFN_OVERWRITEPROMPT | OFN_HIDEREADONLY |
                        OFN_ENABLEHOOK | OFN_EXPLORER | OFN_ENABLESIZING;

            if (!JNU_IsNull(env,fileFilter)) {
                ofn.Flags |= OFN_ENABLEINCLUDENOTIFY;
            }
            ofn.lCustData = (LPARAM)peer;
            ofn.lpfnHook = (LPOFNHOOKPROC)FileDialogHookProc;

            if (multipleMode == JNI_TRUE) {
                ofn.Flags |= OFN_ALLOWMULTISELECT;
            }

            // Save current directory, so we can reset if it changes.
            currentDirectory = new TCHAR[MAX_PATH+1];

            VERIFY(::GetCurrentDirectory(MAX_PATH, currentDirectory) > 0);
        }

        mode = env->GetIntField(target, AwtFileDialog::modeID);

        AwtDialog::CheckInstallModalHook();

        if (useCommonItemDialog) {
            OLE_NEXT_TRY
            GUID fileDialogMode = mode == java_awt_FileDialog_LOAD ? CLSID_FileOpenDialog : CLSID_FileSaveDialog;
            OLE_HRT(pfd.CreateInstance(fileDialogMode));

            jobject jbrDialog = env->GetObjectField(target, AwtFileDialog::jbrDialogID);
            jint hints = env->GetIntField(jbrDialog, AwtFileDialog::hintsID);
            bool folderPickerMode = hints & com_jetbrains_desktop_JBRFileDialog_SELECT_DIRECTORIES_HINT;
            bool fileExclusivePickerMode = hints & com_jetbrains_desktop_JBRFileDialog_SELECT_FILES_HINT;
            if (folderPickerMode && fileExclusivePickerMode) {
                folderPickerMode = fileExclusivePickerMode = false;
            }
            data.ignoreCustomizations = folderPickerMode || fileExclusivePickerMode || mode == java_awt_FileDialog_SAVE;
            data.fileDialog = pfd;
            data.peer = peer;
            SaveCommonDialogLocalizationData(env, jbrDialog, data);
            OLE_HRT(CDialogEventHandler_CreateInstance(&data, IID_PPV_ARGS(&pfde)));
            OLE_HRT(pfd->Advise(pfde, &dwCookie));

            DWORD dwFlags;
            OLE_HRT(pfd->GetOptions(&dwFlags));
            if (multipleMode == JNI_TRUE) {
                dwFlags |= FOS_ALLOWMULTISELECT;
            }
            if (folderPickerMode) {
                dwFlags |= FOS_PICKFOLDERS;
            } else {
                dwFlags |= FOS_FORCEFILESYSTEM;  // Windows bug: in the folder picker mode, it prevents the "Linux" node from appearing
            }
            OLE_HRT(pfd->SetOptions(dwFlags));

            OLE_HRT(pfd->SetTitle(titleBuffer));

            if (!folderPickerMode) {
                auto allFilesDescrStr = reinterpret_cast<jstring>(env->GetObjectField(jbrDialog, allFilesFilterDescriptionID));
                if (allFilesDescrStr != nullptr) {
                    int descriptionLen = env->GetStringLength(allFilesDescrStr);
                    allFilesFilterDescrBuf = new wchar_t[descriptionLen + 1];
                    auto descriptionChars = JNU_GetStringPlatformChars(env, allFilesDescrStr, nullptr);
                    wcsncpy(allFilesFilterDescrBuf, descriptionChars, descriptionLen);
                    JNU_ReleaseStringPlatformChars(env, allFilesDescrStr, descriptionChars);
                    allFilesFilterDescrBuf[descriptionLen] = L'\0';
                }
                auto allFilesFilterDescrPtr = allFilesFilterDescrBuf != nullptr ? allFilesFilterDescrBuf : L"All Files";

                auto descriptionStr = reinterpret_cast<jstring>(env->GetObjectField(jbrDialog, fileFilterDescriptionID));
                auto extensionArray = reinterpret_cast<jobjectArray>(env->GetObjectField(jbrDialog, fileFilterExtensionsID));
                if (descriptionStr != nullptr && extensionArray != nullptr) {
                    int extensionArrayLen = env->GetArrayLength(extensionArray);
                    int filterSpecLen = extensionArrayLen * 3;
                    for (int i = 0; i < extensionArrayLen; ++i) {
                        auto extensionStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(extensionArray, i));
                        filterSpecLen += env->GetStringLength(extensionStr);
                        env->DeleteLocalRef(extensionStr);
                    }
                    fileFilterSpecBuf = new wchar_t[filterSpecLen];
                    for (int i = 0, fsp = 0; i < extensionArrayLen; ++i) {
                        if (i > 0) {
                            fileFilterSpecBuf[fsp++] = L';';
                        }
                        fileFilterSpecBuf[fsp++] = L'*';
                        fileFilterSpecBuf[fsp++] = L'.';
                        auto extensionStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(extensionArray, i));
                        auto extensionChars = JNU_GetStringPlatformChars(env, extensionStr, nullptr);
                        int extensionStrLen = env->GetStringLength(extensionStr);
                        wcsncpy(fileFilterSpecBuf + fsp, extensionChars, extensionStrLen);
                        fsp += extensionStrLen;
                        JNU_ReleaseStringPlatformChars(env, extensionStr, extensionChars);
                        env->DeleteLocalRef(extensionStr);
                    }
                    fileFilterSpecBuf[filterSpecLen - 1] = L'\0';

                    int descriptionLen = env->GetStringLength(descriptionStr);
                    fileFilterDescrBuf = new wchar_t[descriptionLen + 1];
                    auto descriptionChars = JNU_GetStringPlatformChars(env, descriptionStr, nullptr);
                    wcsncpy(fileFilterDescrBuf, descriptionChars, descriptionLen);
                    JNU_ReleaseStringPlatformChars(env, descriptionStr, descriptionChars);
                    fileFilterDescrBuf[descriptionLen] = L'\0';

                    COMDLG_FILTERSPEC rgSpec[] = {
                        {fileFilterDescrBuf, fileFilterSpecBuf},
                        {allFilesFilterDescrPtr, L"*.*"}
                    };
                    OLE_HRT(pfd->SetFileTypes(2, rgSpec));
                } else {
                    COMDLG_FILTERSPEC rgSpec[] = {
                        {allFilesFilterDescrPtr, L"*.*"}
                    };
                    OLE_HRT(pfd->SetFileTypes(1, rgSpec));
                }
                OLE_HRT(pfd->SetFileTypeIndex(1));

                env->DeleteLocalRef(allFilesDescrStr);
                env->DeleteLocalRef(descriptionStr);
                env->DeleteLocalRef(extensionArray);
            }

            {
                IShellItemPtr directoryItem;
                OLE_TRY
                OLE_HRT(CreateShellItem(const_cast<LPTSTR>(static_cast<LPCTSTR>(directoryBuffer)), directoryItem));
                OLE_HRT(pfd->SetFolder(directoryItem));
                OLE_CATCH
            }

            {
                OLE_TRY
                OLE_HRT(pfd->SetFileName(fileBuffer));
                OLE_CATCH
            }

            OLE_CATCH
        }

        if (useCommonItemDialog && SUCCEEDED(OLE_HR)) {
            if (mode == java_awt_FileDialog_LOAD) {
                result = SUCCEEDED(pfd->Show(hwndOwner));
                if (!result && data.forceUseContainerFolder && data.result) {
                    // If the dialog has "failed", but we're in the forceUseContainerFolder mode, then we're supposed to
                    // use the container set in data.result, so just proceed as if the dialog succeeded:
                    result = TRUE;
                }
            } else {
                result = SUCCEEDED(pfd->Show(hwndOwner));
                if (result) {
                    OLE_NEXT_TRY
                    OLE_HRT(pfd->GetResult(&psiResult));
                    CoTaskStringHolder filePath;
                    OLE_HRT(psiResult->GetDisplayName(SIGDN_FILESYSPATH, &filePath));
                    size_t filePathLength = _tcslen(filePath);
                    data.resultSize = (UINT)filePathLength;
                    data.result.Attach(new TCHAR[filePathLength + 1]);
                    _tcscpy_s(data.result, filePathLength + 1, filePath);
                    OLE_CATCH
                    result = SUCCEEDED(OLE_HR);
                }
            }
        } else {
            // show the Win32 file dialog
            if (mode == java_awt_FileDialog_LOAD) {
                result = ::GetOpenFileName(&ofn);
            } else {
                result = ::GetSaveFileName(&ofn);
            }
            // Fix for 4181310: FileDialog does not show up.
            // If the dialog is not shown because of invalid file name
            // replace the file name by empty string.
            if (!result) {
                dlgerr = ::CommDlgExtendedError();
                if (dlgerr == FNERR_INVALIDFILENAME) {
                    _tcscpy_s(fileBuffer, bufferLimit, TEXT(""));
                    if (mode == java_awt_FileDialog_LOAD) {
                        result = ::GetOpenFileName(&ofn);
                    } else {
                        result = ::GetSaveFileName(&ofn);
                    }
                }
            }
        }

        AwtDialog::CheckUninstallModalHook();

        DASSERT(env->GetLongField(peer, AwtComponent::hwndID) == 0L);

        AwtDialog::ModalActivateNextWindow(NULL, target, peer);

        if (!useCommonItemDialog) {
            VERIFY(::SetCurrentDirectory(currentDirectory));
        }

        // Report result to peer.
        if (result) {
            jint length;
            if (useCommonItemDialog) {
                length = (jint) GetBufferLength(data.result, data.resultSize);
            } else {
                length = multipleMode
                         ? (jint) GetBufferLength(ofn.lpstrFile, ofn.nMaxFile)
                         : (jint) _tcslen(ofn.lpstrFile);
            }

            jcharArray jnames = env->NewCharArray(length);
            if (jnames == NULL) {
                throw std::bad_alloc();
            }

            if (useCommonItemDialog) {
                env->SetCharArrayRegion(jnames, 0, length, (jchar *) (LPTSTR) data.result);
            } else {
                env->SetCharArrayRegion(jnames, 0, length, (jchar *) ofn.lpstrFile);
            }
            env->CallVoidMethod(peer, AwtFileDialog::handleSelectedMID, jnames);
            env->DeleteLocalRef(jnames);
        } else {
            env->CallVoidMethod(peer, AwtFileDialog::handleCancelMID);
        }
        DASSERT(!safe_ExceptionOccurred(env));
    } catch (...) {
        delete[] allFilesFilterDescrBuf;
        delete[] fileFilterDescrBuf;
        delete[] fileFilterSpecBuf;

        if (useCommonItemDialog) {
            if (pfd && dwCookie != OLE_BAD_COOKIE) {
                pfd->Unadvise(dwCookie);
            }
        }

        env->DeleteLocalRef(target);
        env->DeleteLocalRef(parent);
        env->DeleteLocalRef(title);
        env->DeleteLocalRef(directory);
        env->DeleteLocalRef(file);
        env->DeleteLocalRef(fileFilter);
        env->DeleteGlobalRef(peer);

        delete[] currentDirectory;
        /* ofn.lpstrFile may have not been set if useCommonItemDialog == true,
         * so use fileBuffer instead */
        delete[] fileBuffer;
        throw;
    }

    if (useCommonItemDialog) {
        if (pfd && dwCookie != OLE_BAD_COOKIE) {
            pfd->Unadvise(dwCookie);
        }
    }

    env->DeleteLocalRef(target);
    env->DeleteLocalRef(parent);
    env->DeleteLocalRef(title);
    env->DeleteLocalRef(directory);
    env->DeleteLocalRef(file);
    env->DeleteLocalRef(fileFilter);
    env->DeleteGlobalRef(peer);

    delete[] currentDirectory;
    /* ofn.lpstrFile may have not been set if useCommonItemDialog == true,
     * so use fileBuffer instead */
    delete[] fileBuffer;
}

BOOL AwtFileDialog::InheritsNativeMouseWheelBehavior() {return true;}

void AwtFileDialog::_DisposeOrHide(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;

    HWND hdlg = (HWND)(env->GetLongField(self, AwtComponent::hwndID));
    if (::IsWindow(hdlg))
    {
        ::SendMessage(hdlg, WM_COMMAND, MAKEWPARAM(IDCANCEL, 0),
                      (LPARAM)hdlg);
    }

    env->DeleteGlobalRef(self);
}

void AwtFileDialog::_ToFront(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;
    HWND hdlg = (HWND)(env->GetLongField(self, AwtComponent::hwndID));
    if (::IsWindow(hdlg))
    {
        ::SetWindowPos(hdlg, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
    }

    env->DeleteGlobalRef(self);
}

void AwtFileDialog::_ToBack(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject self = (jobject)param;
    HWND hdlg = (HWND)(env->GetLongField(self, AwtComponent::hwndID));
    if (::IsWindow(hdlg))
    {
        ::SetWindowPos(hdlg, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }

    env->DeleteGlobalRef(self);
}

// Returns the length of the double null terminated output buffer
UINT AwtFileDialog::GetBufferLength(LPTSTR buffer, UINT limit)
{
    UINT index = 0;
    while ((index < limit) &&
           (buffer[index] != NULL || buffer[index+1] != NULL))
    {
        index++;
    }
    return index;
}

/************************************************************************
 * WFileDialogPeer native methods
 */

extern "C" {

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer_initIDs(JNIEnv *env, jclass cls)
{
    TRY;

    AwtFileDialog::parentID =
        env->GetFieldID(cls, "parent", "Lsun/awt/windows/WComponentPeer;");
    DASSERT(AwtFileDialog::parentID != NULL);
    CHECK_NULL(AwtFileDialog::parentID);

    AwtFileDialog::fileFilterID =
        env->GetFieldID(cls, "fileFilter", "Ljava/io/FilenameFilter;");
    DASSERT(AwtFileDialog::fileFilterID != NULL);
    CHECK_NULL(AwtFileDialog::fileFilterID);

    AwtFileDialog::setHWndMID = env->GetMethodID(cls, "setHWnd", "(J)V");
    DASSERT(AwtFileDialog::setHWndMID != NULL);
    CHECK_NULL(AwtFileDialog::setHWndMID);

    AwtFileDialog::handleSelectedMID =
        env->GetMethodID(cls, "handleSelected", "([C)V");
    DASSERT(AwtFileDialog::handleSelectedMID != NULL);
    CHECK_NULL(AwtFileDialog::handleSelectedMID);

    AwtFileDialog::handleCancelMID =
        env->GetMethodID(cls, "handleCancel", "()V");
    DASSERT(AwtFileDialog::handleCancelMID != NULL);
    CHECK_NULL(AwtFileDialog::handleCancelMID);

    AwtFileDialog::checkFilenameFilterMID =
        env->GetMethodID(cls, "checkFilenameFilter", "(Ljava/lang/String;)Z");
    DASSERT(AwtFileDialog::checkFilenameFilterMID != NULL);
    CHECK_NULL(AwtFileDialog::checkFilenameFilterMID);

    AwtFileDialog::isMultipleModeMID = env->GetMethodID(cls, "isMultipleMode", "()Z");
    DASSERT(AwtFileDialog::isMultipleModeMID != NULL);
    CHECK_NULL(AwtFileDialog::isMultipleModeMID);

    /* java.awt.FileDialog fields */
    cls = env->FindClass("java/awt/FileDialog");
    CHECK_NULL(cls);

    AwtFileDialog::modeID = env->GetFieldID(cls, "mode", "I");
    DASSERT(AwtFileDialog::modeID != NULL);
    CHECK_NULL(AwtFileDialog::modeID);

    AwtFileDialog::dirID = env->GetFieldID(cls, "dir", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::dirID != NULL);
    CHECK_NULL(AwtFileDialog::dirID);

    AwtFileDialog::fileID = env->GetFieldID(cls, "file", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::fileID != NULL);
    CHECK_NULL(AwtFileDialog::fileID);

    AwtFileDialog::filterID =
        env->GetFieldID(cls, "filter", "Ljava/io/FilenameFilter;");
    DASSERT(AwtFileDialog::filterID != NULL);

    AwtFileDialog::jbrDialogID =
        env->GetFieldID(cls, "jbrDialog", "Lcom/jetbrains/desktop/JBRFileDialog;");
    DASSERT(AwtFileDialog::jbrDialogID != NULL);
    CHECK_NULL(AwtFileDialog::jbrDialogID);

    /* com.jetbrains.desktop.JBRFileDialog fields */
    cls = env->FindClass("com/jetbrains/desktop/JBRFileDialog");
    CHECK_NULL(cls);

    AwtFileDialog::openButtonTextID =
        env->GetFieldID(cls, "openButtonText", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::openButtonTextID != NULL);
    CHECK_NULL(AwtFileDialog::openButtonTextID);

    AwtFileDialog::selectFolderButtonTextID =
        env->GetFieldID(cls, "selectFolderButtonText", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::selectFolderButtonTextID != NULL);
    CHECK_NULL(AwtFileDialog::selectFolderButtonTextID);

    AwtFileDialog::allFilesFilterDescriptionID =
        env->GetFieldID(cls, "allFilesFilterDescription", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::allFilesFilterDescriptionID != NULL);
    CHECK_NULL(AwtFileDialog::allFilesFilterDescriptionID);

    AwtFileDialog::fileFilterDescriptionID =
        env->GetFieldID(cls, "fileFilterDescription", "Ljava/lang/String;");
    DASSERT(AwtFileDialog::fileFilterDescriptionID != NULL);
    CHECK_NULL(AwtFileDialog::fileFilterDescriptionID);

    AwtFileDialog::fileFilterExtensionsID =
        env->GetFieldID(cls, "fileFilterExtensions", "[Ljava/lang/String;");
    DASSERT(AwtFileDialog::fileFilterExtensionsID != NULL);
    CHECK_NULL(AwtFileDialog::fileFilterExtensionsID);

    AwtFileDialog::hintsID = env->GetFieldID(cls, "hints", "I");
    DASSERT(AwtFileDialog::hintsID != NULL);
    CHECK_NULL(AwtFileDialog::hintsID);

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer_setFilterString(JNIEnv *env, jclass cls,
                                                     jstring filterDescription)
{
    TRY;

    AwtFileDialog::Initialize(env, filterDescription);

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer__1show(JNIEnv *env, jobject peer)
{
    TRY;

    /*
     * Fix for 4906972.
     * 'peer' reference has to be global as it's used further in another thread.
     */
    jobject peerGlobal = env->NewGlobalRef(peer);

    if (!AwtToolkit::GetInstance().PostMessage(WM_AWT_INVOKE_METHOD,
                             (WPARAM)AwtFileDialog::Show, (LPARAM)peerGlobal)) {
        env->DeleteGlobalRef(peerGlobal);
    }

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer__1dispose(JNIEnv *env, jobject peer)
{
    TRY_NO_VERIFY;

    jobject peerGlobal = env->NewGlobalRef(peer);

    AwtToolkit::GetInstance().SyncCall(AwtFileDialog::_DisposeOrHide,
        (void *)peerGlobal);
    // peerGlobal ref is deleted in _DisposeOrHide

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer__1hide(JNIEnv *env, jobject peer)
{
    TRY;

    jobject peerGlobal = env->NewGlobalRef(peer);

    AwtToolkit::GetInstance().SyncCall(AwtFileDialog::_DisposeOrHide,
        (void *)peerGlobal);
    // peerGlobal ref is deleted in _DisposeOrHide

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer_toFront(JNIEnv *env, jobject peer)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtFileDialog::_ToFront,
                                       (void *)(env->NewGlobalRef(peer)));
    // global ref is deleted in _ToFront

    CATCH_BAD_ALLOC;
}

JNIEXPORT void JNICALL
Java_sun_awt_windows_WFileDialogPeer_toBack(JNIEnv *env, jobject peer)
{
    TRY;

    AwtToolkit::GetInstance().SyncCall(AwtFileDialog::_ToBack,
                                       (void *)(env->NewGlobalRef(peer)));
    // global ref is deleted in _ToBack

    CATCH_BAD_ALLOC;
}

int ScaleDownAbsX(int x, HWND hwnd) {
    int screen = AwtWin32GraphicsDevice::DeviceIndexForWindow(hwnd);
    Devices::InstanceAccess devices;
    AwtWin32GraphicsDevice* device = devices->GetDevice(screen);
    return device == NULL ? x : device->ScaleDownAbsX(x);
}

int ScaleDownAbsY(int y, HWND hwnd) {
    int screen = AwtWin32GraphicsDevice::DeviceIndexForWindow(hwnd);
    Devices::InstanceAccess devices;
    AwtWin32GraphicsDevice* device = devices->GetDevice(screen);
    return device == NULL ? y : device->ScaleDownAbsY(y);
}

jobject AwtFileDialog::_GetLocationOnScreen(void *param)
{
    JNIEnv *env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    jobject result = NULL;
    HWND hwnd = (HWND)env->GetLongField((jobject)param, AwtComponent::hwndID);

    if (::IsWindow(hwnd))
    {
        RECT rect;
        VERIFY(::GetWindowRect(hwnd, &rect));
        result = JNU_NewObjectByName(env, "java/awt/Point", "(II)V",
                                     ScaleDownAbsX(rect.left, hwnd),
                                     ScaleDownAbsY(rect.top, hwnd));
    }

    if (result != NULL)
    {
        jobject resultRef = env->NewGlobalRef(result);
        env->DeleteLocalRef(result);
        return resultRef;
    }
    else
    {
        return NULL;
    }
}

/*
 * Class:     sun_awt_windows_WFileDialogPeer
 * Method:    getLocationOnScreen
 * Signature: ()Ljava/awt/Point;
 */
JNIEXPORT jobject JNICALL
Java_sun_awt_windows_WFileDialogPeer_getLocationOnScreen(JNIEnv *env,
                                                                 jobject peer) {
    TRY;

    jobject peerRef = env->NewGlobalRef(peer);
    jobject resultRef = (jobject)AwtToolkit::GetInstance().SyncCall(
        (void*(*)(void*))AwtFileDialog::_GetLocationOnScreen, (void *)peerRef);
    env->DeleteGlobalRef(peerRef);

    if (resultRef != NULL)
    {
        jobject result = env->NewLocalRef(resultRef);
        env->DeleteGlobalRef(resultRef);
        return result;
    }

    return NULL;

    CATCH_BAD_ALLOC_RET(NULL);
}

} /* extern "C" */

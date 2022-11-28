/* this ALWAYS GENERATED file contains the RPC client stubs */


 /* File created by MIDL compiler version 8.01.0626 */
/* at Tue Jan 19 06:14:07 2038
 */
/* Compiler settings for build\x86_64\interfaces\nvdaController\nvdaController.idl, build\x86_64\interfaces\nvdaController\nvdaController.acf:
    Oicf, W1, Zp8, env=Win64 (32b run), target_arch=AMD64 8.01.0626 
    protocol : dce , ms_ext, c_ext, robust
    error checks: allocation ref bounds_check enum stub_data 
    VC __declspec() decoration level: 
         __declspec(uuid()), __declspec(selectany), __declspec(novtable)
         DECLSPEC_UUID(), MIDL_INTERFACE()
*/
/* @@MIDL_FILE_HEADING(  ) */

#if defined(_M_AMD64)


#pragma warning( disable: 4049 )  /* more than 64k source lines */
#if _MSC_VER >= 1200
#pragma warning(push)
#endif

#pragma warning( disable: 4211 )  /* redefine extern to static */
#pragma warning( disable: 4232 )  /* dllimport identity*/
#pragma warning( disable: 4024 )  /* array to pointer mapping*/

#include <string.h>

#include "nvdaController.h"

#include <Rpc.h>
#include <rpcndr.h>

#define TYPE_FORMAT_STRING_SIZE   7                                 
#define PROC_FORMAT_STRING_SIZE   141                               
#define EXPR_FORMAT_STRING_SIZE   1                                 
#define TRANSMIT_AS_TABLE_SIZE    0            
#define WIRE_MARSHAL_TABLE_SIZE   0            

typedef struct _nvdaController_MIDL_TYPE_FORMAT_STRING
    {
    short          Pad;
    unsigned char  Format[ TYPE_FORMAT_STRING_SIZE ];
    } nvdaController_MIDL_TYPE_FORMAT_STRING;

typedef struct _nvdaController_MIDL_PROC_FORMAT_STRING
    {
    short          Pad;
    unsigned char  Format[ PROC_FORMAT_STRING_SIZE ];
    } nvdaController_MIDL_PROC_FORMAT_STRING;

typedef struct _nvdaController_MIDL_EXPR_FORMAT_STRING
    {
    long          Pad;
    unsigned char  Format[ EXPR_FORMAT_STRING_SIZE ];
    } nvdaController_MIDL_EXPR_FORMAT_STRING;


static const RPC_SYNTAX_IDENTIFIER  _RpcTransferSyntax = 
{{0x8A885D04,0x1CEB,0x11C9,{0x9F,0xE8,0x08,0x00,0x2B,0x10,0x48,0x60}},{2,0}};

#if defined(_CONTROL_FLOW_GUARD_XFG)
#define XFG_TRAMPOLINES(ObjectType)\
static unsigned long ObjectType ## _UserSize_XFG(unsigned long * pFlags, unsigned long Offset, void * pObject)\
{\
return  ObjectType ## _UserSize(pFlags, Offset, pObject);\
}\
static unsigned char * ObjectType ## _UserMarshal_XFG(unsigned long * pFlags, unsigned char * pBuffer, void * pObject)\
{\
return ObjectType ## _UserMarshal(pFlags, pBuffer, pObject);\
}\
static unsigned char * ObjectType ## _UserUnmarshal_XFG(unsigned long * pFlags, unsigned char * pBuffer, void * pObject)\
{\
return ObjectType ## _UserUnmarshal(pFlags, pBuffer, pObject);\
}\
static void ObjectType ## _UserFree_XFG(unsigned long * pFlags, void * pObject)\
{\
ObjectType ## _UserFree(pFlags, pObject);\
}
#define XFG_TRAMPOLINES64(ObjectType)\
static unsigned long ObjectType ## _UserSize64_XFG(unsigned long * pFlags, unsigned long Offset, void * pObject)\
{\
return  ObjectType ## _UserSize64(pFlags, Offset, pObject);\
}\
static unsigned char * ObjectType ## _UserMarshal64_XFG(unsigned long * pFlags, unsigned char * pBuffer, void * pObject)\
{\
return ObjectType ## _UserMarshal64(pFlags, pBuffer, pObject);\
}\
static unsigned char * ObjectType ## _UserUnmarshal64_XFG(unsigned long * pFlags, unsigned char * pBuffer, void * pObject)\
{\
return ObjectType ## _UserUnmarshal64(pFlags, pBuffer, pObject);\
}\
static void ObjectType ## _UserFree64_XFG(unsigned long * pFlags, void * pObject)\
{\
ObjectType ## _UserFree64(pFlags, pObject);\
}
#define XFG_BIND_TRAMPOLINES(HandleType, ObjectType)\
static void* ObjectType ## _bind_XFG(HandleType pObject)\
{\
return ObjectType ## _bind((ObjectType) pObject);\
}\
static void ObjectType ## _unbind_XFG(HandleType pObject, handle_t ServerHandle)\
{\
ObjectType ## _unbind((ObjectType) pObject, ServerHandle);\
}
#define XFG_TRAMPOLINE_FPTR(Function) Function ## _XFG
#else
#define XFG_TRAMPOLINES(ObjectType)
#define XFG_TRAMPOLINES64(ObjectType)
#define XFG_BIND_TRAMPOLINES(HandleType, ObjectType)
#define XFG_TRAMPOLINE_FPTR(Function) Function
#endif


extern const nvdaController_MIDL_TYPE_FORMAT_STRING nvdaController__MIDL_TypeFormatString;
extern const nvdaController_MIDL_PROC_FORMAT_STRING nvdaController__MIDL_ProcFormatString;
extern const nvdaController_MIDL_EXPR_FORMAT_STRING nvdaController__MIDL_ExprFormatString;

#define GENERIC_BINDING_TABLE_SIZE   0            


/* Standard interface: __MIDL_itf_nvdaController_0000_0000, ver. 0.0,
   GUID={0x00000000,0x0000,0x0000,{0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00}} */


/* Standard interface: NvdaController, ver. 1.0,
   GUID={0xDFF50B99,0xF7FD,0x4ca7,{0xA8,0x2C,0xDA,0xEB,0x3E,0x02,0x52,0x95}} */

handle_t nvdaControllerBindingHandle;


static const RPC_CLIENT_INTERFACE NvdaController___RpcClientInterface =
    {
    sizeof(RPC_CLIENT_INTERFACE),
    {{0xDFF50B99,0xF7FD,0x4ca7,{0xA8,0x2C,0xDA,0xEB,0x3E,0x02,0x52,0x95}},{1,0}},
    {{0x8A885D04,0x1CEB,0x11C9,{0x9F,0xE8,0x08,0x00,0x2B,0x10,0x48,0x60}},{2,0}},
    0,
    0,
    0,
    0,
    0,
    0x00000000
    };
RPC_IF_HANDLE nvdaController_NvdaController_v1_0_c_ifspec = (RPC_IF_HANDLE)& NvdaController___RpcClientInterface;

extern const MIDL_STUB_DESC NvdaController_StubDesc;

static RPC_BINDING_HANDLE NvdaController__MIDL_AutoBindHandle;


/* [comm_status][fault_status] */ error_status_t __stdcall nvdaController_testIfRunning( void)
{

    CLIENT_CALL_RETURN _RetVal;

    _RetVal = NdrClientCall2(
                  ( PMIDL_STUB_DESC  )&NvdaController_StubDesc,
                  (PFORMAT_STRING) &nvdaController__MIDL_ProcFormatString.Format[0],
                  0);
    return ( error_status_t  )_RetVal.Simple;
    
}


/* [comm_status][fault_status] */ error_status_t __stdcall nvdaController_speakText( 
    /* [string][in] */ const wchar_t *text)
{

    CLIENT_CALL_RETURN _RetVal;

    _RetVal = NdrClientCall2(
                  ( PMIDL_STUB_DESC  )&NvdaController_StubDesc,
                  (PFORMAT_STRING) &nvdaController__MIDL_ProcFormatString.Format[32],
                  text);
    return ( error_status_t  )_RetVal.Simple;
    
}


/* [comm_status][fault_status] */ error_status_t __stdcall nvdaController_cancelSpeech( void)
{

    CLIENT_CALL_RETURN _RetVal;

    _RetVal = NdrClientCall2(
                  ( PMIDL_STUB_DESC  )&NvdaController_StubDesc,
                  (PFORMAT_STRING) &nvdaController__MIDL_ProcFormatString.Format[70],
                  0);
    return ( error_status_t  )_RetVal.Simple;
    
}


/* [comm_status][fault_status] */ error_status_t __stdcall nvdaController_brailleMessage( 
    /* [string][in] */ const wchar_t *message)
{

    CLIENT_CALL_RETURN _RetVal;

    _RetVal = NdrClientCall2(
                  ( PMIDL_STUB_DESC  )&NvdaController_StubDesc,
                  (PFORMAT_STRING) &nvdaController__MIDL_ProcFormatString.Format[102],
                  message);
    return ( error_status_t  )_RetVal.Simple;
    
}


#if !defined(__RPC_WIN64__)
#error  Invalid build platform for this stub.
#endif

static const nvdaController_MIDL_PROC_FORMAT_STRING nvdaController__MIDL_ProcFormatString =
    {
        0,
        {

	/* Procedure testIfRunning */

			0x32,		/* FC_BIND_PRIMITIVE */
			0x68,		/* Old Flags:  comm or fault/decode */
/*  2 */	NdrFcLong( 0x0 ),	/* 0 */
/*  6 */	NdrFcShort( 0x0 ),	/* 0 */
/*  8 */	NdrFcShort( 0x8 ),	/* X64 Stack size/offset = 8 */
/* 10 */	NdrFcShort( 0x0 ),	/* 0 */
/* 12 */	NdrFcShort( 0x8 ),	/* 8 */
/* 14 */	0x44,		/* Oi2 Flags:  has return, has ext, */
			0x1,		/* 1 */
/* 16 */	0xa,		/* 10 */
			0x1,		/* Ext Flags:  new corr desc, */
/* 18 */	NdrFcShort( 0x0 ),	/* 0 */
/* 20 */	NdrFcShort( 0x0 ),	/* 0 */
/* 22 */	NdrFcShort( 0x0 ),	/* 0 */
/* 24 */	NdrFcShort( 0x0 ),	/* 0 */

	/* Return value */

/* 26 */	NdrFcShort( 0x70 ),	/* Flags:  out, return, base type, */
/* 28 */	NdrFcShort( 0x0 ),	/* X64 Stack size/offset = 0 */
/* 30 */	0x10,		/* FC_ERROR_STATUS_T */
			0x0,		/* 0 */

	/* Procedure speakText */

/* 32 */	0x32,		/* FC_BIND_PRIMITIVE */
			0x68,		/* Old Flags:  comm or fault/decode */
/* 34 */	NdrFcLong( 0x0 ),	/* 0 */
/* 38 */	NdrFcShort( 0x1 ),	/* 1 */
/* 40 */	NdrFcShort( 0x10 ),	/* X64 Stack size/offset = 16 */
/* 42 */	NdrFcShort( 0x0 ),	/* 0 */
/* 44 */	NdrFcShort( 0x8 ),	/* 8 */
/* 46 */	0x46,		/* Oi2 Flags:  clt must size, has return, has ext, */
			0x2,		/* 2 */
/* 48 */	0xa,		/* 10 */
			0x1,		/* Ext Flags:  new corr desc, */
/* 50 */	NdrFcShort( 0x0 ),	/* 0 */
/* 52 */	NdrFcShort( 0x0 ),	/* 0 */
/* 54 */	NdrFcShort( 0x0 ),	/* 0 */
/* 56 */	NdrFcShort( 0x0 ),	/* 0 */

	/* Parameter text */

/* 58 */	NdrFcShort( 0x10b ),	/* Flags:  must size, must free, in, simple ref, */
/* 60 */	NdrFcShort( 0x0 ),	/* X64 Stack size/offset = 0 */
/* 62 */	NdrFcShort( 0x4 ),	/* Type Offset=4 */

	/* Return value */

/* 64 */	NdrFcShort( 0x70 ),	/* Flags:  out, return, base type, */
/* 66 */	NdrFcShort( 0x8 ),	/* X64 Stack size/offset = 8 */
/* 68 */	0x10,		/* FC_ERROR_STATUS_T */
			0x0,		/* 0 */

	/* Procedure cancelSpeech */

/* 70 */	0x32,		/* FC_BIND_PRIMITIVE */
			0x68,		/* Old Flags:  comm or fault/decode */
/* 72 */	NdrFcLong( 0x0 ),	/* 0 */
/* 76 */	NdrFcShort( 0x2 ),	/* 2 */
/* 78 */	NdrFcShort( 0x8 ),	/* X64 Stack size/offset = 8 */
/* 80 */	NdrFcShort( 0x0 ),	/* 0 */
/* 82 */	NdrFcShort( 0x8 ),	/* 8 */
/* 84 */	0x44,		/* Oi2 Flags:  has return, has ext, */
			0x1,		/* 1 */
/* 86 */	0xa,		/* 10 */
			0x1,		/* Ext Flags:  new corr desc, */
/* 88 */	NdrFcShort( 0x0 ),	/* 0 */
/* 90 */	NdrFcShort( 0x0 ),	/* 0 */
/* 92 */	NdrFcShort( 0x0 ),	/* 0 */
/* 94 */	NdrFcShort( 0x0 ),	/* 0 */

	/* Return value */

/* 96 */	NdrFcShort( 0x70 ),	/* Flags:  out, return, base type, */
/* 98 */	NdrFcShort( 0x0 ),	/* X64 Stack size/offset = 0 */
/* 100 */	0x10,		/* FC_ERROR_STATUS_T */
			0x0,		/* 0 */

	/* Procedure brailleMessage */

/* 102 */	0x32,		/* FC_BIND_PRIMITIVE */
			0x68,		/* Old Flags:  comm or fault/decode */
/* 104 */	NdrFcLong( 0x0 ),	/* 0 */
/* 108 */	NdrFcShort( 0x3 ),	/* 3 */
/* 110 */	NdrFcShort( 0x10 ),	/* X64 Stack size/offset = 16 */
/* 112 */	NdrFcShort( 0x0 ),	/* 0 */
/* 114 */	NdrFcShort( 0x8 ),	/* 8 */
/* 116 */	0x46,		/* Oi2 Flags:  clt must size, has return, has ext, */
			0x2,		/* 2 */
/* 118 */	0xa,		/* 10 */
			0x1,		/* Ext Flags:  new corr desc, */
/* 120 */	NdrFcShort( 0x0 ),	/* 0 */
/* 122 */	NdrFcShort( 0x0 ),	/* 0 */
/* 124 */	NdrFcShort( 0x0 ),	/* 0 */
/* 126 */	NdrFcShort( 0x0 ),	/* 0 */

	/* Parameter message */

/* 128 */	NdrFcShort( 0x10b ),	/* Flags:  must size, must free, in, simple ref, */
/* 130 */	NdrFcShort( 0x0 ),	/* X64 Stack size/offset = 0 */
/* 132 */	NdrFcShort( 0x4 ),	/* Type Offset=4 */

	/* Return value */

/* 134 */	NdrFcShort( 0x70 ),	/* Flags:  out, return, base type, */
/* 136 */	NdrFcShort( 0x8 ),	/* X64 Stack size/offset = 8 */
/* 138 */	0x10,		/* FC_ERROR_STATUS_T */
			0x0,		/* 0 */

			0x0
        }
    };

static const nvdaController_MIDL_TYPE_FORMAT_STRING nvdaController__MIDL_TypeFormatString =
    {
        0,
        {
			NdrFcShort( 0x0 ),	/* 0 */
/*  2 */	
			0x11, 0x8,	/* FC_RP [simple_pointer] */
/*  4 */	
			0x25,		/* FC_C_WSTRING */
			0x5c,		/* FC_PAD */

			0x0
        }
    };

static const unsigned short NvdaController_FormatStringOffsetTable[] =
    {
    0,
    32,
    70,
    102
    };


static const COMM_FAULT_OFFSETS NvdaController_CommFaultOffsets[] = 
{
	{ -1, -1 },	/* ia64 Offsets for testIfRunning */
	{ -1, -1 },	/* ia64 Offsets for speakText */
	{ -1, -1 },	/* ia64 Offsets for cancelSpeech */
	{ -1, -1 } 	/* ia64 Offsets for brailleMessage */
};


static const MIDL_STUB_DESC NvdaController_StubDesc = 
    {
    (void *)& NvdaController___RpcClientInterface,
    MIDL_user_allocate,
    MIDL_user_free,
    &nvdaControllerBindingHandle,
    0,
    0,
    0,
    0,
    nvdaController__MIDL_TypeFormatString.Format,
    1, /* -error bounds_check flag */
    0x50002, /* Ndr library version */
    0,
    0x8010272, /* MIDL Version 8.1.626 */
    NvdaController_CommFaultOffsets,
    0,
    0,  /* notify & notify_flag routine table */
    0x1, /* MIDL flag */
    0, /* cs routines */
    0,   /* proxy/server info */
    0
    };
#if _MSC_VER >= 1200
#pragma warning(pop)
#endif


#endif /* defined(_M_AMD64)*/


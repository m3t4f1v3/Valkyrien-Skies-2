/*
 * Minimal C shim so the game can start and stop an Nsight GPU Trace itself.
 *
 * Why this exists: Nsight's time-based trigger (--start-after-ms) never completed a trace against
 * this application. The session connects, arms, and then dies with "Activity session destroyed,
 * connection error encountered" -- at every delay tried, on both a headless gamescope display and
 * the real desktop, privileged and not, with and without --no-timeout, and with nothing further in
 * --verbose. The documented alternative is --start-with-ngfx-sdk, where the APPLICATION decides the
 * moment: it calls NGFX_GPUTrace_StartTrace_OpenGL when the scene is in the state worth measuring.
 * That also removes the guesswork about when the client has finished loading.
 *
 * The SDK is header-only and dispatches through a globals table that NGFX_Do_InitializeActivity
 * fills in by dlopening Nsight's target library, so it cannot be called from Java directly. This
 * wrapper exposes three plain no-argument entry points for the JVM to invoke.
 *
 * Every function returns the NGFX_Result as an int; 0 is NGFX_RESULT_OK. Nothing here is specific
 * to VS2, so it stays out of the mod's source tree.
 *
 *   build: autotest/ngfx-shim/build.sh
 */
#include <stdint.h>

#include "NGFX_GPUTrace_OpenGL.h"

/* Load Nsight's library and bind the GPU-Trace entry points. Call once, after the GL context
 * exists -- the SDK resolves OpenGL-specific functions at this point. */
int vs_ngfx_init(void)
{
    NGFX_GPUTrace_InitializeActivity_OpenGL_Params params;
    params.version = NGFX_GPUTrace_InitializeActivity_OpenGL_Params_VER;
    return (int) NGFX_GPUTrace_InitializeActivity_OpenGL(&params);
}

/* Arm the trace. Nsight must have been launched with --start-with-ngfx-sdk. */
int vs_ngfx_activate(void)
{
    NGFX_GPUTrace_ActivateTrace_OpenGL_Params params;
    params.version = NGFX_GPUTrace_ActivateTrace_OpenGL_Params_VER;
    return (int) NGFX_GPUTrace_ActivateTrace_OpenGL(&params);
}

int vs_ngfx_start(void)
{
    NGFX_GPUTrace_StartTrace_OpenGL_Params params;
    params.version = NGFX_GPUTrace_StartTrace_OpenGL_Params_VER;
    return (int) NGFX_GPUTrace_StartTrace_OpenGL(&params);
}

int vs_ngfx_stop(void)
{
    NGFX_GPUTrace_StopTrace_OpenGL_Params params;
    params.version = NGFX_GPUTrace_StopTrace_OpenGL_Params_VER;
    params.flags = 0; /* no special stop behaviour */
    return (int) NGFX_GPUTrace_StopTrace_OpenGL(&params);
}

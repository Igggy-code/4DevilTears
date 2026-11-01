// 4DevilTears: private copy of libmspack's LZX decoder (symbols renamed so they never clash
// with the copy inside the ReXGlue runtime). Used to read entries of the game's .arc files.
#pragma once
#define lzxd_init dmc4_lzxd_init
#define lzxd_decompress dmc4_lzxd_decompress
#define lzxd_free dmc4_lzxd_free
#define lzxd_set_output_length dmc4_lzxd_set_output_length
#define lzxd_set_reference_data dmc4_lzxd_set_reference_data
#ifdef __cplusplus
extern "C" {
#endif
#include "third_party/mspack/mspack.h"
#include "third_party/mspack/lzx.h"
#ifdef __cplusplus
}
#endif

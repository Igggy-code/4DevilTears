// 4DevilTears: boot sequence.
//
// uViolence (DTI name, ctor sub_82487FA0, vtable 0x820BFFA0) is the first screen after boot:
// "colours may look wrong / input lag depending on your TV" (texture id\init\tv-adjust_NOMIP,
// per language). Its update, vtable[6] = sub_82487BC8, steps byte +23 through
// 0 start, 1 fade in, 2 wait, 3 hold, 4 fade out; any button press sets it to 5 = finished,
// byte +60 is the image alpha. On a phone it is not relevant: state 0 runs normally (one
// frame, alpha 0 - it also sets up things the boot sequence relies on), then the screen
// finishes as if a button were pressed, before it ever becomes visible.
// The publisher logos that follow are kept.
#include <cstdint>

#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

extern "C" void __imp__sub_82487BC8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82487BC8(PPCContext& __restrict ctx, uint8_t* base) {
  uint8_t* self = base + ctx.r3.u32;
  if (self[23] >= 1 && self[23] <= 4) {
    self[23] = 5;   // finished
    self[60] = 0;   // fully transparent
    static bool logged = false;
    if (!logged) {
      logged = true;
      REXKRNL_INFO("BOOT: TV disclaimer skipped");
    }
  }
  __imp__sub_82487BC8(ctx, base);
}

// 4DevilTears: performance hooks.
//
// GPU wait spin. The game's render thread waits for the GPU (read pointer of the command ring
// / a fence) in a D3D-style busy loop: sub_821663F0 calls sub_82161DA8 again and again, and
// that step only spins (db16cyc pauses), reads the time and checks a flag. On the Xbox 360
// that kept a hardware thread busy; here it keeps a whole phone CPU core at 100% for nothing -
// heat and power the SoC then takes away from the GPU (lower GPU clocks on Tensor).
// With dmc4_spin_sleep_us > 0 every step first sleeps that long, so the thread waits
// asleep; the game's logic is unchanged (same checks, same timeouts).
#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

REXCVAR_DEFINE_INT32(dmc4_spin_sleep_us, 100, "4DevilTears",
                     "Sleep (microseconds) in each step of the game's GPU wait loop; 0 = spin "
                     "like the original");

namespace {
std::atomic<uint64_t> g_spin_steps{0};
}

extern "C" void __imp__sub_82161DA8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82161DA8(PPCContext& __restrict ctx, uint8_t* base) {
  const int32_t us = REXCVAR_GET(dmc4_spin_sleep_us);
  if (us > 0) {
    std::this_thread::sleep_for(std::chrono::microseconds(us));
  }
  uint64_t n = g_spin_steps.fetch_add(1, std::memory_order_relaxed) + 1;
  if (n == 1) {
    REXKRNL_INFO("PERF: GPU wait loop, {} us sleep per step", us);
  }
  __imp__sub_82161DA8(ctx, base);
}

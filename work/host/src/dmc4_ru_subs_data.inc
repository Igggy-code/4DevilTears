// 4DevilTears: placeholder for the Russian translation data.
//
// The real file (texts of the corrected Russian subtitles and menus, cue times, glyph cells) is
// built from the Russian edition's script and ships only inside the APK - the script belongs to
// its rights holders, so it is not published here. With this placeholder the project builds
// and runs; the translation is simply not applied (the edition check below never matches).
static const uint32_t kRuSubsVersion = 0;
static const uint64_t kFontRuBase = 0;  // matches no font: Dmc4ApplyRuSubtitles() does nothing
static const uint32_t kFontBaseRows = 40;
static const uint16_t kFontCells[] = {0};
static const uint8_t kFontCellBlocks[16 * 64] = {};
static const RuMenuMsg kMenuMsgs[] = {{}};
static const RuTexFix kTexFixes[] = {{}};
static const RuArcJob kArcJobs[] = {{}};
static const RuSubScene kScenes[] = {{}};

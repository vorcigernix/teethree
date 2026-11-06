# Ghostty terminal runtime

This directory vendors the pure Android terminal renderer and JNI bridge from T3 Code,
Ghostty headers/shared libraries at revision `9f62873bf195e4d8a762d768a1405a5f2f7b1697`,
and the MesloLGS NF terminal fonts. License and provenance are in `LICENSE` and
`THIRD_PARTY_NOTICES.md`.

The Kotlin bridge retains its `expo.modules.t3terminal` package because the JNI
exports use that name. No Expo or React Native runtime is included. CMake, headers,
fonts, and shared libraries resolve entirely within this project.

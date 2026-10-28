# HUD / Windows tuning profile compatibility
- Preserve CarrotTuningProfile schema1 and longitudinal-only scope. Never interpret a full184-key PC session file as a deployable profile.
- On every app/firmware update test all five golden profiles, strict unit/type/range checks, roundtrip export/import, old unsupported endpoint behavior, readback and no automatic retry after uncertain writes.
- Keep shared golden JSON identical in app assets and pc/tuning_lab. Maintain Windows self-tests before an app release.
- Preserve user files on incompatible schema/corruption. No automatic migration/clamp/key deletion or automatic profile reapplication after updating.
- Profile server receipts are external persistent data; source/OTA cleanup must not delete /data/community/tuning_profiles.
- Keep no-network Windows behavior, existing HUD compatibility, clone monitoring and independent Panda policy.

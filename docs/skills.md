# Project skill map

The user requested these installed skills. Apply them when their corresponding work is reached; do not invent features solely to exercise a skill.

| Skill | Project use |
| --- | --- |
| android-cli | SDK packages, emulator execution, app inspection and official Android documentation |
| testing-setup | Android instrumentation and Compose semantics; preserve the specified JVM JUnit 5 stack |
| mobile-android-design | Material 3, native touch targets, state hoisting and accessibility |
| kotlin-coroutines-flows | Structured concurrency, cancellation, IO dispatch and eventual checkout StateFlow |
| kotlin-ktor-patterns | Thin routes, serialization, plugins and testApplication |
| supabase-postgres-best-practices | PostgreSQL schema, constraints, tenant-aware queries, pooling and lock ordering |
| vercel-react-best-practices | Client rendering and fetching with the specified Vite/TanStack Query stack |
| docker-compose-patterns | Pinned images, readiness ordering, volumes and network boundaries |
| apple-design | Restraint, clear typography, immediate feedback, spatial continuity and reduced motion |
| ponytail | Small complete changes, no premature abstraction, coherent commits |
| test-driven-development | Behavioral red/green checks before implementation |
| systematic-debugging | Evidence and root-cause investigation before fixes |
| verification-before-completion | Fresh check output before claims and commits |

`overall.md` and the user's instructions govern project-specific choices. Examples in skills are guidance, not permission to add Hilt/Koin, switch to Next.js/SWR or Supabase hosting, add redundant screenshot frameworks, or rename the required Compose files. Keep the specified `infra/compose/dev.yml` and `prod.yml` layout. Do not adopt a generic skill's auth/error example in place of TapFlow's stronger security contract.

The portal's system typeface, restrained green surfaces and minimal press feedback follow the requested Apple direction. Native Android screens keep Material semantics and Android navigation conventions. Gestures or decorative animation are added only when a concrete workflow needs them.

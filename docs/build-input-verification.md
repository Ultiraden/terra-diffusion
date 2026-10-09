# Pinned build-input verification

NeoForge is the default supported build. `settings.gradle` includes `common` and
`neoforge`; Fabric and its pinned Loom 1.13.6 plugin are configured only with
`-PincludeFabric=true`. The opt-in is also forwarded by nested build tasks, so
Fabric variant tasks retain their existing behavior. No runtime generator or
artifact pin changes are involved.

The build keeps strict SHA-256 dependency verification. Issue
[ulti-world #84](https://github.com/Ultiraden/ulti-world/issues/84) adds the two
published inputs first required by a fresh Loom configuration and the additional
pinned JUnit BOM module metadata required by the affected-cache NeoForge artifact
build. On 2026-10-09, each file was downloaded independently from its official
Fabric Maven or Maven Central repository,
hashed with SHA-256 and SHA-512, and compared to both official checksum sidecars.
The downloaded bytes also matched the affected default Gradle cache exactly.

| Input | SHA-256 | Official source |
| --- | --- | --- |
| `net.fabricmc:intermediary:1.21.1`, classifier `v2` | `e8bd6a4c39e235ee2dc73063558610775c72a2572fa0c6b32915b149074012a6` | [JAR](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar), [SHA-256](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar.sha256), [SHA-512](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar.sha512) |
| `net.fabricmc:intermediary:1.21.1` POM | `b6ec5f03e95bea232b6960ed077dd0a765a3aabfd74732ee600eb77d2651adf4` | [POM](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.pom), [SHA-256](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.pom.sha256), [SHA-512](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.pom.sha512) |
| `org.junit:junit-bom:5.7.2` Gradle module metadata | `f3bceb1c59dd4f6993f4304dffa580172b8df65a76cd36fa4fd92c0578d28ad8` | [Module](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.7.2/junit-bom-5.7.2.module), [SHA-256](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.7.2/junit-bom-5.7.2.module.sha256), [SHA-512](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.7.2/junit-bom-5.7.2.module.sha512) |

These checksums verify published inputs; they do not establish reproducibility
of Loom-generated archives in a new cache. The accepted Windows/JDK artifact
reproducibility recipe uses its recorded verified dependency cache, as documented
by the pack. Default NeoForge source checks also exercise the affected default
Gradle cache independently, without configuring the unused Fabric project.

## Fresh generated-mappings limitation

Before making NeoForge the default, the source-check invocation in the new
issue-84 worktree, with `JAVA_HOME` pinned
to Temurin 21.0.12.1+1-LTS and `GRADLE_USER_HOME` unset, passed these intermediary
inputs. Configuration then stopped on a checksum mismatch for the locally
generated `loom:mappings:layered+hash.288375958` JAR:

- Accepted verified-cache SHA-256:
  `f2968966bcbe3467154dce1a73982cb16551a2395552338f88cd21ffd7721025`.
- Newly generated default-cache SHA-256:
  `6efd34029b1b3cac9adfcd02a89f4d40ae1a9454284ac3e0c26a92fdbbf1ba48`.
- Both JARs contain exactly the same `mappings/mappings.tiny` bytes, SHA-256
  `1edac3700ea16b935f3748711e21928aeb98383fbbefbf6abce485362e2d66ea`.
- ZIP DOS timestamps and NTFS timestamp extra fields differ between the archives.

The official [Loom 1.13.6 source archive](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.13.6/fabric-loom-1.13.6-sources.jar)
was independently downloaded and matched its [SHA-256 sidecar](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.13.6/fabric-loom-1.13.6-sources.jar.sha256),
`a9b017e160007ae57b8663f304bd5048be38cfa82503ad012b6a441c468cd637`.
`LayeredMappingsFactory.writeMapping` calls `ZipUtils.add`, which writes ZIP
filesystem entries without normalizing their timestamps. Adding each newly
generated outer checksum would not provide a reusable fresh-cache build recipe.
The existing generated-mappings checksum remains unchanged. No verification
bypass or broad trust rule was introduced.

## NeoForge validation

With the supported default settings, a clean common source build in the new
maintenance worktree passed `:common:compileJava`, `:common:compileTestJava` and
`:common:check`, including all 93 catalog checks, with `GRADLE_USER_HOME` unset.
The project list contained only `common` and `neoforge`; no Fabric tasks or Loom
configuration appeared. This used the existing default Gradle user cache and
newly generated NeoForm outputs, rather than claiming an entirely empty cache.

With `-PincludeFabric=true` and the recorded verified cache, project/task
inspection passed and included `fabric` with its existing variant tasks and
`Dml` aliases. The nested root and Fabric build command lines included
`-PincludeFabric=true`, preserving the opt-in across their Gradle subprocesses.
This check configured Fabric and inspected routing; it did not certify a fresh
Fabric artifact build or change the generated-mappings checksum.
Explicit Fabric opt-in in the affected default cache also reproduced the strict
generated-mappings failure; the regenerated archive still contained the exact
accepted mappings bytes. The supported NeoForge default avoids that unused
configuration while preserving its verification gate when Fabric is enabled.

Both final variants were rebuilt using the recorded verified Gradle cache,
Temurin 21.0.12.1+1-LTS on Windows, and the exact JVM/toolchain paths with discovery
and download disabled. Each ran `:neoforge:clean :common:catalogTest
:neoforge:build` with its `useCuda` or `useDml` property. Their complete bytes
matched the accepted #4 stable artifacts and the pack source lock:

| Variant | Size (bytes) | SHA-512 |
| --- | ---: | --- |
| Windows/DirectML | 7,178,191 | `67718f5466b15f24ccecc3032e36f179bea66af680c5a4d3d1b75affc5144e7a74e8151117c75e4426b7f7327243274ff48ef0e3f29c61b9e9c1e026fd4b1dff` |
| CUDA | 547,369,025 | `bf782e8aa6ddf23b17bdeb4043700da7e733feff4a36a723c7106af27789976d33832603f81de742460bfc0945f4296f9cc04ba42e7566f8e06ec2f70e7ca3d1` |

The same clean Windows/CUDA artifact recipe also passed with `GRADLE_USER_HOME`
unset, after independently verifying and adding the JUnit BOM module checksum.
Both resulting files matched these hashes and the full accepted artifact bytes.
This exercised newly generated NeoForm outputs in the affected existing default
cache. It does not claim a completely empty Gradle cache, another JDK patch or
another operating system will produce identical bytes.

The original published Windows JAR was independently downloaded, matched its
pinned outer SHA-512, and its nested DirectML JAR matched both the pinned SHA-256
and local input bytes. `scripts/bootstrap-dml.ps1` retained its checksum gates.
No GPU, client, server or world was launched for this build maintenance.

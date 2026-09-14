#!/usr/bin/env bash
# Fetches the benchmark corpus into corpus/: the .kt files of each project at a pinned revision
# (sparse, blob-filtered, depth 1, so only the Kotlin sources are downloaded).
set -euo pipefail
cd "$(dirname "$0")/.."

while read -r name url sha; do
  dir=corpus/$name
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$sha" ]; then
    echo "$name: already at ${sha:0:10}"
    continue
  fi
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" remote add origin "$url"
  git -C "$dir" sparse-checkout set --no-cone '*.kt'
  git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$sha"
  git -C "$dir" checkout -q FETCH_HEAD
  echo "$name: ${sha:0:10}  $(find "$dir" -name '*.kt' | wc -l | tr -d ' ') .kt files"
done <<'EOF'
arrow                 https://github.com/arrow-kt/arrow.git                    6ab9df9a506e02edf838c702069478ab71f8de84
Exposed               https://github.com/JetBrains/Exposed.git                 2155404863401e0257f89c301509cde59e7becd1
kotlinpoet            https://github.com/square/kotlinpoet.git                 b7f6d400ee2aa60b7a6ec2263e65efb5fe6f8e4d
kotlinx.coroutines    https://github.com/Kotlin/kotlinx.coroutines.git         7e8b5a405c834a5e50ee20e90449d0c492db8601
kotlinx.serialization https://github.com/Kotlin/kotlinx.serialization.git      397bb560096fcb7b2a9363741690cca3d28124ba
ktor                  https://github.com/ktorio/ktor.git                       88c0026f6fee2af55d96a20620f2e700b912b334
okio                  https://github.com/square/okio.git                       e8dad1a9a86b96b04e51aa941e834b400114667c
coil                  https://github.com/coil-kt/coil.git                      ec724ef5a72c787cf910de3cae3cc703b3337f25
detekt                https://github.com/detekt/detekt.git                     6fa04a63c8ad9056706852ea7cf6560385fef2fd
spring-framework      https://github.com/spring-projects/spring-framework.git  99b991b6f378c9804381a013530cb8393094b8d9
congo-grammars        https://github.com/congo-cc/congo-grammars.git           f1443fee4b58b1ff49721b27a43e9f1f2253acf6
EOF

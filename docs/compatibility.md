# Compatibility policy

Tessera records `0.1.0` as its first binary and TASTy compatibility baseline.
There is no earlier artifact to compare while building the baseline release, so
MiMa and TASTy-MiMa intentionally receive an empty previous-artifact set for
`0.1.0` and its prerelease qualifiers.

For later versions, every published core, designs, and laws artifact on JVM,
Scala.js, and Scala Native is compared against:

```text
io.github.canardlapin:<platform artifact>:0.1.0
```

The full compatibility gate is:

```text
sbt -batch compatibilityAll
```

MiMa covers binary linkage and TASTy-MiMa covers Scala 3 retyping
compatibility. Neither tool proves semantic compatibility; behavioral laws,
golden locks, and release notes remain separate requirements.

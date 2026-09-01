# Voxy — Au Naturel fork

**A fork of [MCRcortex/voxy](https://github.com/MCRcortex/voxy). All original
work, and the great majority of the code here, is by MCRcortex.**

## Please do not contact MCRcortex about this fork

MCRcortex has specifically asked not to be given support requests arising from
forks. **Do not raise bugs, issues or questions with them about anything in this
repository.** If you hit a problem here, it is this fork's problem, not theirs —
and if you are looking for the actual project, go upstream.

## What this is

A genuine fork, not a downstream branch. It does **not** aim to stay compatible
with upstream, and it implements its own features, bug fixes and design
philosophies, which will diverge over time.

- Targets **Forge 1.20.1**.
- Built to run against the Au Naturel forks of **Embeddium** and **Oculus**, and
  targeting the Au Naturel fork of **Photon** shaders.
- Compatibility with anything else, upstream included, is not a goal.

Notable work here includes heightmap-derived LOD terrain shadows (a clipmap
source feeding a world-anchored shadow map), distant-generation fixes, and
assorted rendering changes tied to the above.

## Distribution

See `LICENSE.md` — copyright is MCRcortex's, and the licence asks that the mod
not be redistributed. **No built artifacts are published from this repository.**
The inherited GitHub Actions workflows are disabled
(`.github/workflows/*.disabled`) and `.gitignore` excludes `build/` and `*.jar`.
This exists for transparency and source storage. Build it yourself if you need
it.

---

Voxy is an LoD rendering mod for minecraft
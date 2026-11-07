# FSR Upscaler for VulkanMod

An AMD FSR 1.0 (EASU + RCAS) spatial upscaler for VulkanMod, targeting
Minecraft 26.1.2. Renders the world at a 360p- or 720p-based internal
resolution and upscales to your real window size, with a YACL config screen,
a native entry in VulkanMod's own Video Settings, and upscaling enabled by
default when Beryl is detected.
Every line of code has a comment explaining it, allowing for anyone to read the code and develop a similar project by themselves.

## How this README changed

The previous version of this project guessed at VulkanMod's internal class
names from search results alone. You then uploaded VulkanMod's actual
release jar. This sandbox has no decompiler and no network, so a small
hand-written `.class` file parser and bytecode disassembler
(`classdump.py` / `disasm.py`, not shipped in the mod itself — just the tools
used to inspect the jar) were used to read the real constant pool, method
signatures, and in one case the actual bytecode of a method body. Every
class/method named below was confirmed this way, not guessed.

## What's confirmed against the real jar

* **`net.vulkanmod.vulkan.Renderer`** — real class. `endFrame()` is a real
public method; `getInstance()`, `getMainPass()`, `getSwapChain()`,
`getCommandBuffer()` are all real and used as shown.
* **`net.vulkanmod.vulkan.Vulkan.getVkDevice()`** — the real static VkDevice
accessor, used directly in `FsrUpscalePass`.
* **`net.vulkanmod.vulkan.pass.DefaultMainPass`** — disassembling
`createResources()` showed that VulkanMod's main render target normally
**is the swapchain's own framebuffer** (full output resolution) — there's
no separate lower-res target to just resize. The only place a literal
width/height is passed to `Framebuffer.builder(...)` is a 10x10 startup
placeholder used before the swapchain has images. `InternalResolutionMixin`
now exploits exactly that: it redirects `SwapChain.hasImages()` to always
report `false` while the upscaler is on (forcing that placeholder branch to
always run) and rewrites its hardcoded `(10, 10)` into our chosen preset's
real resolution. This is a genuine, bytecode-confirmed hook, not a guess —
though see the caveat left as a comment in that file about
`createResources()` possibly re-running more often than necessary; profile
after a real build and add caching there if needed.
* **`net.vulkanmod.vulkan.framebuffer.Framebuffer`** (and `SwapChain`, which
extends it) — `getWidth()`, `getHeight()`, `getColorAttachment()`,
`getColorAttachmentView()`, `builder(int,int,int,boolean)`, `cleanUp(boolean)`
are all real and used in `FsrUpscalePass` and `RendererMixin`.
* **`net.vulkanmod.vulkan.texture.VulkanImage.getId()`** — real method,
the raw `VkImage` handle used for the no-RCAS copy path.
* **`net.vulkanmod.config.gui.ModSettingsRegistry` / `ModSettingsEntry`** —
a genuine, public mod-settings API. `ModSettingsRegistry.INSTANCE .addModEntry(...)` registers an entry that appears natively in VulkanMod's
Video Settings. This replaced a much riskier earlier plan (mixin-injecting
a button into `VOptionScreen`'s layout) entirely — `VOptionScreenMixin` has
been deleted. `VulkanModSettingsIntegration.java` builds our settings as
real `SwitchOption`/`RangeOption`/`CyclingOption` instances via
`Page.of(...).block(...).add(...).done()`, all confirmed real
classes/constructors from the jar.

## What's still unverified (flagged `VERIFY` in the code)

* `Framebuffer$Builder`'s `(IIIZ)` constructor is treated as
`(width, height, level, createImages)`. This is strongly implied by the
field order and by the one confirmed call site, but wasn't checked against
a second independent call site.
* `Identifier.fromNamespaceAndPath(...)` (used for the Video Settings icon)
is written as the modern Minecraft 26.1.2 equivalent of the old
`ResourceLocation` constructor, but wasn't checked against Minecraft's own
jar — only VulkanMod's.
* The shader-compile Gradle task (`.comp` → `.spv` via `glslc`) still needs
to be added; see VulkanMod's own `build.gradle` for its pattern.
* Several dependency versions in `gradle.properties` (Yarn mappings, Fabric
API, YACL, Mod Menu) are still marked for a Modrinth/Fabric-meta check.

## On Beryl specifically

Unchanged: Beryl is All-Rights-Reserved, so none of its code was downloaded,
decompiled, or read. `BerylCompat.java` only calls Fabric Loader's public
`isModLoaded("beryl")` check.

## Performance expectations on an i5-1035G1

Unchanged from before: its integrated UHD graphics make this GPU-bound in
most cases, which is exactly when FSR 1 helps most. Turn on the debug
overlay (`showDebugOverlay`) to measure real frame time per preset instead
of guessing.


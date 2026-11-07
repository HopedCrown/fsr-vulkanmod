# Jar inspection tools

These aren't part of the mod — they're the two small scripts used to read
VulkanMod's real jar in this session, since the sandbox had no decompiler.

- `classdump.py <jar> <path/To/Class.class> [refs]` — lists a class's real
  fields and method signatures (default), or every Methodref/Fieldref it
  references (with `refs`), straight from the constant pool.
- `disasm.py <jar> <path/To/Class.class> <methodName>` — disassembles one
  method's actual bytecode into readable mnemonics. This is how
  `DefaultMainPass.createResources()` was traced.

Example:
    python3 classdump.py VulkanMod.jar net/vulkanmod/vulkan/Renderer.class
    python3 disasm.py VulkanMod.jar net/vulkanmod/vulkan/pass/DefaultMainPass.class createResources

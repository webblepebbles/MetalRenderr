# MetalRender

**MetalRender** is a custom rendering backend for Minecraft.  
It uses **Apple Metal** to replace Sodium's backend rendering to offer faster frames on Mac!
It is still currently in ALpha, no results are promised. 

---

## Dependencies

| Dependency | Necessity | Where to get |
|---|---|---|
| Sodium | Necessary | [Modrinth](https://modrinth.com/mod/sodium) |
| Fabric | Necessary | [Fabric's official page](https://fabricmc.net/use/installer/) |
| Fabric API | Necessary | [Modrinth](https://modrinth.com/mod/fabric-api) |
| Ramen noodles | Necessary for survival | Find it yourself |

## Features

- Uses **Metal** for rendering on macOS  
- Works with **Fabric Loader** and **Sodium**  
- Checks your hardware before starting  
- Turns off safely if Metal is not supported  

---

# Notes
- Only works on Metal (macOS)
- Will still load on other GPUs but won't offer any additional benefits or changes
- Shaders are not yet supported yet, they might be added in the future
- This should be compatible with most other mods, if it isn't please add a Issue
  

  
---

## Install

1. Download the latest release from [Releases](../../releases).  
2. Put the `.jar` file in your Minecraft `mods/` folder.  
3. Make sure you have:
   - Minecraft **1.21.8+**  
   - Fabric Loader **0.15+**  
   - Fabric API **0.131.0+**  

---

## Build (For Developers)

```bash
git clone https://github.com/webblepebbles/metalrender.git
cd metalrender
./gradlew build

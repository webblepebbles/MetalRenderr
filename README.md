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
| Java JVM >21 | Necessary | [Oracle](https://www.oracle.com/au/java/technologies/downloads/#java21) |

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

# FAQs
- Why is this version diffrent from the Modrinth version?
  
  It is diffrent because I can't make a new version for every bug fix, performance buff or Library edit. It is inefficent so I would make a new version for Modrinth
  every stable release and major bug clearance (a group of bugs that I fixed) or a new feature etc etc. I cannot flood the Modrinth page with 20 new releases every 5
  days or so.

- When will Forge/Neoforge/Quilt be added?

  I probably won't make support for other loaders in the future but for now, only Fabric is supported because its the most lightweight and popular. 

- Can I run MetalRender on non-Apple Silicon hardware?

  Yes you can! But there just might be a crash, best case scenario it just doesn't offer any benefits. I'm working to add other optimisation features that don't need
  Apple Silicon to run so that even Intel/AMD/Nvidia people can enjoy MetalRender. You can expect that maybe next year, I'm busy with school and everything!

- Where can I put suggestions?

  There is a Suggestion tag on the Issues tab now! Please share your suggestions for MetalRender, I'm open to all suggestions and thanks for your help making
  MetalRender better for everyone!
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
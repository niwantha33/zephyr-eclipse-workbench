# Standard Eclipse project workflows

## Create a project

Use **File > New > Zephyr Project**. Choose a completed Zephyr setup folder,
application location and exact board/SoC/CPU. The project uses Eclipse's native
Build, Clean, Debug and project properties controls.

## Open a project from a folder or archive

1. Choose **File > Open Projects from File System...**.
2. Choose **Directory** for an application or its parent folder, or **Archive**
   for an exported project ZIP. Keep **Detect and configure project natures**
   enabled. Nested-project detection can find applications inside a wrapper folder.
3. Select the application folders and finish. The installed configurator appears
   as **Zephyr Application**. Both existing Eclipse projects and standalone
   applications containing `find_package(Zephyr ...)` are supported.
4. Use **Project > Build Project**. A saved `.zew` board target is reused. If the
   application has no target, Build opens **Properties > Zephyr** to select its
   environment and exact board. Save those settings and the build proceeds.

The normal **General > Existing Projects into Workspace** importer also works
for applications that already contain `.project`. Projects already present in
the workspace can simply be opened again. Existing open projects are checked
at Workbench startup and before native builds. No separate repair command or
remove-and-reimport step is needed.

A single completed local environment, or an environment explicitly chosen in
Project Properties, is bound automatically. If several environments are
available without a selected default, choose one in **Properties > Zephyr**.
Another project's board is never used as a default target for a new application.
Target discovery in Properties reads the selected checkout's hardware catalogue.
Opening a project does not download dependencies or invoke CMake/West; dependency
preparation runs when the project is built.

Applications can be opened before selecting an environment or board. A single
configuration problem in Eclipse's Problems view explains the missing selection;
background builds wait for it instead of running generic CMake.

## Configure, build and clean

**Project Properties > Zephyr** contains the environment, board/vendor/SoC/CPU
filters and build configuration. Debug and Release have separate managed build
folders. Use **Project > Build Project**, **Project > Clean** and the standard
Debug controls. Projects retain their source locations when opened from folders.

## Export and reopen

Use **File > Export > General > Archive File** to create a ZIP, or
**General > File System** to copy a project folder. Include the application
sources, `CMakeLists.txt`, configuration/overlay files, `.project`, `.cproject`
when present, and `.zew/project.json` or the legacy `.zew/project.properties`.
The `.zew` profile contains the portable board selection. Eclipse's local
persistent properties contain the setup path; that machine-specific binding is
not required in the exported project.

Build outputs such as `.zew-build` and `build` can be deselected in the standard
export wizard. On the destination machine, complete Zephyr setup first, then
open the archive or copied folder through **Open Projects from File System**.
The board is retained and the local environment is rebound automatically when
it is unambiguous. No custom archive format or export wizard is required.

## Automatic compatibility handling

Zephyr applications imported as generic CDT CMake are automatically switched to
the managed West builder. The generic CMake nature and `cmake.run.*` build
configurations are removed. Original `.project` and `.cproject` files are backed
up in `.zew/import-backup` on the first conversion; source, `prj.conf`, linked
resources and existing build outputs are preserved. A pre-build check also
handles the order in which Eclipse's generic and Zephyr configurators run.
Ordinary CMake applications and unrelated vendor builders retain their workflows.

The dedicated **File > Import > Zephyr > Existing Zephyr Application** wizard
remains available as an alternative. Native Eclipse handles project-name/location
conflicts. Zephyr's own scanner bounds discovery to 12 levels, 20,000 directories,
500 applications and 1 MiB per CMake file, skipping common generated/tool folders
and upstream Zephyr source trees. Applications that select Zephyr only indirectly
through custom scripts are not detected automatically.

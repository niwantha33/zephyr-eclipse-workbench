# Import existing Zephyr applications

The Zephyr import wizard now follows a root-directory/project-list flow.
It imports applications in place: it does not copy their source into the
Eclipse workspace and does not convert CCS or STM32 non-Zephyr firmware.

1. Extract an application ZIP first. Keep the extracted sources in their final
   location, for example `C:\projects`.
2. Choose **File > Import > Zephyr > Existing Zephyr Application**.
3. Browse to either the application itself or its parent directory. Leave
   **Search nested folders** enabled for a parent containing several projects
   or an extra folder from ZIP extraction. Browse starts a search; the **Search**
   button also refreshes it after changing the path or search option.
4. Check the applications to import. Their real application folders appear in
   the list. If names conflict, highlight a row and edit **Selected row's project
   name**. **Select All** and **Deselect All** affect the checkboxes.
5. Click **Next**. Choose a completed **Ready setup folder** and load its hardware
   catalogue. Filter by vendor, board or core and select the exact default target
   for applications that have no saved Workbench target.
6. Click **Next** to review each project name, original folder, exact target and
   environment. Saved `.zew` targets are preserved; a default selection does not
   overwrite them.
7. Click **Finish**. Projects appear in Project Explorer. Eclipse's **Build
   Automatically** preference applies; import does not launch an additional
   explicit build. With auto-build off, select **Project > Build Project**.

For the test ZIP, selecting `C:\projects` should find the inner folder containing
`Zephyr_RTOS_Analysis_Demo\CMakeLists.txt`, even if extraction added a wrapper
directory. That inner application, rather than the wrapper, becomes the project.

## Discovery and conflict handling

Discovery reads `CMakeLists.txt` and recognises a direct `find_package(Zephyr ...)`
call, including multiline calls and a quoted package name. It does not execute
CMake. Examples in comments/strings do not qualify a generic CMake project.
Applications that select Zephyr only indirectly through custom scripts are not
auto-detected by this first scanner; keep that limitation visible rather than
silently importing a generic CMake tree.

The scanner skips common generated/tool folders, filesystem links and upstream
Zephyr source trees. Search is cancelable and bounded to 12 levels, 20,000
directories, 500 applications and 1 MiB per CMake file. Unreadable/oversized input
and depth limits are reported. Choose a narrower source parent for large trees.

Already imported locations are unavailable. Duplicate project names can be
renamed; overlapping parent/child applications cannot be selected together.
Existing Workbench Eclipse descriptions are reused while binding the chosen
local environment. Projects with another Eclipse builder receive guidance to
**General > Existing Projects into Workspace**, preserving their native workflow.
Source code, `prj.conf` and saved board profiles are not replaced. Creating the
Eclipse project and editor integration adds/updates project metadata in place.

The import rechecks all selected applications before creating projects. Import
is not a transaction across multiple directories: if cancellation or a filesystem
error occurs after some imports complete, those projects are retained and named
in the error. The remaining selection can be retried; no application is deleted.

This path requires a completed Workbench managed environment. It is not an
installer for arbitrary external SDK layouts. Run **Zephyr > Set Up Zephyr** if
there is no ready environment, or choose an already completed installation.

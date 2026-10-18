# Big Door Opener

This is an add-on for the amazing plugin [Big Doors](https://www.spigotmc.org/resources/58669/).

It allows you to create doors, which open and close based on various conditions.


Please see the [wiki](https://github.com/eldoriarpg/BigDoorOpener/wiki) for further Information.

The latest stable release can be found on [spigot](https://www.spigotmc.org/resources/80805/)

## KINGDOM Paper 1.21.8 build

This fork provides the BigDoorsOpener JAR used with Paper 1.21.8 and Java 21.
The only source change builds with JDK 21 while targeting Java 17 bytecode;
the original plugin behavior is unchanged.

Install these plugins in the server's `plugins/` directory, then restart:

1. [BigDoors 0.1.8.70](https://github.com/PimvanderLoos/BigDoors/releases/tag/0.1.8.70) by Pim van der Loos (GPL-3.0).
2. [BigDoorsPhysics 2.4.4](https://modrinth.com/plugin/bigdoorsphysics) by bonn2 (AGPL-3.0).
3. BigDoorsOpener 2.5.3 from [this fork's releases](https://github.com/d1zify/BigDoorOpener/releases) (AGPL-3.0).

If you use JavaScript conditions in BigDoorsOpener, install a NashornJS script
engine as well. The three plugins above were verified to enable together in a
local Paper test server; door behavior must still be checked in game.

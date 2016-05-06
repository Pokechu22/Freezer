# Freeze test plugin

A better way of freezing players that keeps them from moving clientside rather than continuously moving them back when they try to move.  Based off of [cuberite's freeze logic](https://github.com/cuberite/cuberite/blob/b3d4e0fca665502b727f0088a3a20aac1b9ad073/src/Entities/Player.cpp#L2524-L2553).

## Theory

When players are flying, all movement (including vertical movement) is determined by the fly speed value.  This means that clients can be frozen in place.  If both velocity and position are reset after any movement, then the client stays still.  This can even be done while still allowing a player to look around.
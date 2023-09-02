An ImageJ plugin made for quick video analysis. It tracks zebra fish based on a nearest-neighbor single-particle-tracking algorithm and returns an array of positions for each zebra fish, speed profile and average speed.

The tracking algorithm:
=======================
The user is first asked to find a frame where all zebrafish can be distinguished one from another. That is, when they are at least a few pixels apart. The user clicks on the ones that are to be tracked.
To cope with heterogenous lighting, the nearest neighbor (identified as the darkest spot) is searched in a limited radius around the current position. To avoid tracking the wrong zebra fish larva when multiple come close to each other, this search radius is reduced in such cases.

Handling larvae collision:
==========================
Collisions are tricky to handle: the colliding dark spots merge into one and separate again after the collision. Since zebra fish larvae are not pure mechanical systems, it seemed hard to deliver an algorithm which would perfectly predict which dark spot was which before and after collision. Hence, a discontinuity is made in the trajectory when the collision occurs. The user is then asked to stitch the trajectories together.

Computing results:
==================
The trajectories are displayed on the image, so that the user can estimate the precision of the algorithm. Position arrays for each zebrafish are added to a result table, speed profile and average speeds to respective ones.
![til](zf_tracking_demo.gif =200x200)

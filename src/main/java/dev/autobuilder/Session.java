package dev.autobuilder;

import org.bukkit.Location;

/** Per-player working state (also holds the GUI settings). */
final class Session {
    Blueprint blueprint;                 // as loaded (not rotated)
    Location origin;
    int rotation;
    BuildJob.Supply supply = BuildJob.Supply.INVENTORY;
    boolean ignoreAir;
    boolean ignoreWater;
    int builders = 1;
    double speed;
    BuildJob.Pattern pattern = BuildJob.Pattern.UP;
    BuildJob.Mode mode = BuildJob.Mode.SHARED;
    Preview.Type preview = Preview.Type.OUTLINE;
    boolean adminView = true;            // admins can switch to the survival view
    Location chest;                      // linked supply chest / barrel
    Location pos1, pos2;
    BuildJob job;
    BuildJob pending;                    // priced build waiting for the player's confirmation
    BuildJob.Quote pendingQuote;
    String pendingKey;
    String hireKey;                      // hire fee already paid for this schematic + origin

    Session(double speed) {
        this.speed = speed;
    }
}

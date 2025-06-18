package org.mitre.openaria.smoothing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.newTreeSet;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

import org.mitre.caasd.commons.DataCleaner;
import org.mitre.caasd.commons.Distance;
import org.mitre.caasd.commons.Speed;
import org.mitre.openaria.core.Point;
import org.mitre.openaria.core.Track;
import org.mitre.openaria.core.formats.Format;
import org.mitre.openaria.core.formats.Formats;
import org.mitre.openaria.core.formats.ariacsv.AriaCsvHit;
import org.mitre.openaria.util.ProcessingErrorCounter;


/**
 * Filters out low speed points at the front and back of a track that have a common altitude
 * measurement. This filter can be useful when attempting to remove ground-data from Tracks (in
 * cases where you only are interested in the airborne section of a track).
 */
public class TrimSlowMovingPointsWithSimilarAltitudes<T> implements DataCleaner<Track<T>> {

    private final double speedLimitInKnots;
    private final Distance groundAltitudeTolerance;
    private final int minNumberPoints;

    /**
     * @param speedLimit         Points below this speed are removed from the front and back of the
     *                           track.
     * @param groundAltTolerance The amount a track's point can climb while still being considered
     *                           "on the ground" (assuming its speed is also low).
     * @param minNumberPoints    If the trimmed Track is smaller than this the entire Track is
     *                           removed
     */
    public TrimSlowMovingPointsWithSimilarAltitudes(Speed speedLimit, Distance groundAltTolerance, int minNumberPoints) {
        requireNonNull(speedLimit);
        requireNonNull(groundAltTolerance);
        checkArgument(speedLimit.isGreaterThan(Speed.ZERO), "The speed limit must be positive");
        checkArgument(minNumberPoints >= 1, "The minimum number of points must be at least 1");
        this.speedLimitInKnots = speedLimit.inKnots();
        this.groundAltitudeTolerance = groundAltTolerance;
        this.minNumberPoints = minNumberPoints;
    }

    @Override
    public Optional<Track<T>> clean(Track<T> track) {
        // Check for null speeds before doing anything else
        for (Point<T> point : track.points()) {
            if (point.speed() == null) {
                ProcessingErrorCounter.getInstance().increment();
                System.err.println("Skipping track due to null speed. Track ID: " + track.trackId());
                Format format = Formats.getFormat("csv");
                String[] trk = format.asRawStrings(track);
                System.err.println(track.size() + " points");
                for (String line : trk) {
                    System.err.println(line);
                }
                return Optional.empty();
            }
        }

        TreeSet<Point<T>> points = newTreeSet(track.points());

        try {
            removePointsFromBeginning(points);
            removePointsFromEnd(points);
        } catch (Exception e) {
            ProcessingErrorCounter.getInstance().increment();
            e.printStackTrace();
            System.out.println("Failed on Track: ");
            Format format = Formats.getFormat("csv");
            String[] trk0 = format.asRawStrings(track);
            System.out.println(track.size() + " points");
            for (String line : trk0) {
                System.out.println(line);
            }
            return Optional.empty();
        }

        return (points.size() >= minNumberPoints)
                ? Optional.of(Track.of(points))
                : Optional.empty();
    }

    private void removePointsFromBeginning(NavigableSet<Point<T>> points) {
        if (points.isEmpty()) return;
        Distance startingAlt = points.first().altitude();
        while (!points.isEmpty() && isSlowAndInRange(points.first(), startingAlt)) {
            points.pollFirst();
        }
    }

    private void removePointsFromEnd(NavigableSet<Point<T>> points) {
        if (points.isEmpty()) return;
        Distance endingAlt = points.last().altitude();
        while (!points.isEmpty() && isSlowAndInRange(points.last(), endingAlt)) {
            points.pollLast();
        }
    }

    private boolean isSlowAndInRange(Point<T> p, Distance estimatedGroundAlt) {
        Speed speed = p.speed();
        if (speed == null) {
            // Treat as not slow and in range if speed is missing (shouldn't happen now)
            return false;
        }
        boolean isSlow = speed.inKnots() < speedLimitInKnots;
        boolean hasSimilarAlt = p.altitude().minus(estimatedGroundAlt).abs().isLessThan(groundAltitudeTolerance);
        return isSlow && hasSimilarAlt;
    }
}
/*
 * Copyright (C) 2015, BMW Car IT GmbH
 *
 * Author: Sebastian Mattheis <sebastian.mattheis@bmw-carit.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package io.sharedstreets.barefoot.matcher;

import io.sharedstreets.barefoot.markov.Filter;
import io.sharedstreets.barefoot.markov.KState;
import io.sharedstreets.barefoot.road.Heading;
import io.sharedstreets.barefoot.roadmap.*;
import io.sharedstreets.barefoot.spatial.SpatialOperator;
import io.sharedstreets.barefoot.topology.Cost;
import io.sharedstreets.barefoot.topology.Router;
import io.sharedstreets.barefoot.util.Stopwatch;
import io.sharedstreets.barefoot.util.Tuple;
import com.esri.core.geometry.GeometryEngine;
import com.esri.core.geometry.WktExportFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Matcher filter for Hidden Markov Model (HMM) map matching. It is a HMM filter (@{link Filter})
 * and determines emission and transition probabilities for map matching with HMM.
 */
public class Matcher extends Filter<MatcherCandidate, MatcherTransition, MatcherSample> {
    private static final Logger logger = LoggerFactory.getLogger(Matcher.class);

    private RoadMap map;
    private Router<RoadEdge, RoadPoint> router;
    private Cost<RoadEdge> cost;
    private SpatialOperator spatial;

    private int maxFailure = Integer.MAX_VALUE;
    private double sig2 = Math.pow(5d, 2);
    private double sigA = Math.pow(10d, 2);
    private double sqrt_2pi_sig2 = Math.sqrt(2d * Math.PI * sig2);
    private double sqrt_2pi_sigA = Math.sqrt(2d * Math.PI * sigA);
    private double lambda = 0d;
    private double radius = 200;
    private double distance = 15000;

    /**
     * Creates a HMM map matching filter for some map, router, cost function, and spatial operator.
     *
     * @param map {@link RoadMap} object of the map to be matched to.
     * @param router {@link Router} object to be used for route estimation.
     * @param cost Cost function to be used for routing.
     * @param spatial Spatial operator for spatial calculations.
     */
    public Matcher(RoadMap map, Router<RoadEdge, RoadPoint> router, Cost<RoadEdge> cost,
                   SpatialOperator spatial) {
        this.map = map;
        this.router = router;
        this.cost = cost;
        this.spatial = spatial;
    }

    public Matcher(){

    }

    public int getMaxFailure() {
        return this.maxFailure;
    }

    public void setMaxFailure(int maxFailure) {
        this.maxFailure = maxFailure;
    }

    /**
     * Gets standard deviation in meters of gaussian distribution that defines emission
     * probabilities.
     *
     * @return Standard deviation in meters of gaussian distribution that defines emission
     *         probabilities.
     */
    public double getSigma() {
        return Math.sqrt(this.sig2);
    }

    /**
     * Sets standard deviation in meters of gaussian distribution for defining emission
     * probabilities (default is 5 meters).
     *
     * @param sigma Standard deviation in meters of gaussian distribution for defining emission
     *        probabilities (default is 5 meters).
     */
    public void setSigma(double sigma) {
        this.sig2 = Math.pow(sigma, 2);
        this.sqrt_2pi_sig2 = Math.sqrt(2d * Math.PI * sig2);
    }

    /**
     * Gets lambda parameter of negative exponential distribution defining transition probabilities.
     *
     * @return Lambda parameter of negative exponential distribution defining transition
     *         probabilities.
     */
    public double getLambda() {
        return this.lambda;
    }

    /**
     * Sets lambda parameter of negative exponential distribution defining transition probabilities
     * (default is 0.0). It uses adaptive parameterization, if lambda is set to 0.0.
     *
     * @param lambda Lambda parameter of negative exponential distribution defining transition
     *        probabilities.
     */
    public void setLambda(double lambda) {
        this.lambda = lambda;
    }

    /**
     * Gets maximum radius for candidate selection in meters.
     *
     * @return Maximum radius for candidate selection in meters.
     */
    public double getMaxRadius() {
        return this.radius;
    }

    /**
     * Sets maximum radius for candidate selection in meters (default is 100 meters).
     *
     * @param radius Maximum radius for candidate selection in meters.
     */
    public void setMaxRadius(double radius) {
        this.radius = radius;
    }

    /**
     * Gets maximum transition distance in meters.
     *
     * @return Maximum transition distance in meters.
     */
    public double getMaxDistance() {
        return this.distance;
    }

    /**
     * Sets maximum transition distance in meters (default is 15000 meters).
     *
     * @param distance Maximum transition distance in meters.
     */
    public void setMaxDistance(double distance) {
        this.distance = distance;
    }

    @Override
    protected Set<Tuple<MatcherCandidate, Double>> candidates(Set<MatcherCandidate> predecessors,
            MatcherSample sample) {
        if (logger.isTraceEnabled()) {
            logger.trace("finding candidates for sample {} {}",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(sample.time()),
                    GeometryEngine.geometryToWkt(sample.point(), WktExportFlags.wktExportPoint));
        }

        Set<RoadPoint> points_ = map.index().radius(sample.point(), radius);
        Set<RoadPoint> points = new HashSet<>(Minset.minimize(points_));

        Map<Long, RoadPoint> map = new HashMap<>();
        for (RoadPoint point : points) {
            map.put(point.edge().id(), point);
        }
        for (MatcherCandidate predecessor : predecessors) {
            RoadPoint point = map.get(predecessor.point().edge().id());
            if (point != null && point.edge() != null
                    && spatial.distance(point.geometry(),
                            predecessor.point().geometry()) < getSigma()
                    && ((point.edge().heading() == Heading.forward
                            && point.fraction() < predecessor.point().fraction())
                            || (point.edge().heading() == Heading.backward
                                    && point.fraction() > predecessor.point().fraction()))) {
                points.remove(point);
                points.add(predecessor.point());
            }
        }

        Set<Tuple<MatcherCandidate, Double>> candidates = new HashSet<>();
        logger.debug("{} ({}) candidates", points.size(), points_.size());

        for (RoadPoint point : points) {
            double dz = spatial.distance(sample.point(), point.geometry());
            double emission = 1 / sqrt_2pi_sig2 * Math.exp((-1) * dz * dz / (2 * sig2));
            if (!Double.isNaN(sample.azimuth())) {
                double da = sample.azimuth() > point.azimuth()
                        ? Math.min(sample.azimuth() - point.azimuth(),
                                360 - (sample.azimuth() - point.azimuth()))
                        : Math.min(point.azimuth() - sample.azimuth(),
                                360 - (point.azimuth() - sample.azimuth()));
                emission *= Math.max(1E-2, 1 / sqrt_2pi_sigA * Math.exp((-1) * da / (2 * sigA)));
            }

            MatcherCandidate candidate = new MatcherCandidate(point);
            candidates.add(new Tuple<>(candidate, emission));

            logger.trace("{} {} {}", candidate.id(), dz, emission);
        }

        return candidates;
    }

    @Override
    protected Tuple<MatcherTransition, Double> transition(
            Tuple<MatcherSample, MatcherCandidate> predecessor,
            Tuple<MatcherSample, MatcherCandidate> candidate) {

        return null;
    }

    @Override
    protected Map<MatcherCandidate, Map<MatcherCandidate, Tuple<MatcherTransition, Double>>> transitions(
            final Tuple<MatcherSample, Set<MatcherCandidate>> predecessors,
            final Tuple<MatcherSample, Set<MatcherCandidate>> candidates) {

        if (logger.isTraceEnabled()) {
            logger.trace("finding transitions for sample {} {} with {} x {} candidates",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(candidates.one().time()),
                    GeometryEngine.geometryToWkt(candidates.one().point(),
                            WktExportFlags.wktExportPoint),
                    predecessors.two().size(), candidates.two().size());
        }

        Stopwatch sw = new Stopwatch();
        sw.start();

        final Set<RoadPoint> targets = new HashSet<>();
        for (MatcherCandidate candidate : candidates.two()) {
            targets.add(candidate.point());
        }

        final AtomicInteger count = new AtomicInteger();
        final Map<MatcherCandidate, Map<MatcherCandidate, Tuple<MatcherTransition, Double>>> transitions =
                new ConcurrentHashMap<>();
        final double base =
                1.0 * spatial.distance(predecessors.one().point(), candidates.one().point()) / 60;
        final double bound = Math.max(1000d, Math.min(distance,
                ((candidates.one().time() - predecessors.one().time()) / 1000) * 100));

        for (final MatcherCandidate predecessor : predecessors.two()) {

            Map<MatcherCandidate, Tuple<MatcherTransition, Double>> map = new HashMap<>();
            Map<RoadPoint, List<RoadEdge>> routes =
                    router.route(predecessor.point(), targets, cost, new Distance(), bound);
            sw.stop();

            logger.trace("{} routes ({} ms)", routes.size(), sw.ms());

            for (MatcherCandidate candidate : candidates.two()) {
                List<RoadEdge> edges = routes.get(candidate.point());

                if (edges == null) {
                    continue;
                }

                Route route = new Route(predecessor.point(), candidate.point(), edges);

                // According to Newson and Krumm 2009, transition probability is lambda *
                // Math.exp((-1.0) * lambda * Math.abs(dt - route.length())), however, we
                // experimentally choose lambda * Math.exp((-1.0) * lambda * Math.max(0,
                // route.length() - dt)) to avoid unnecessary routes in case of u-turns.

                double beta = lambda == 0
                        ? (2.0 * Math.max(1d,
                                candidates.one().time() - predecessors.one().time()) / 1000)
                        : 1 / lambda;

                double transition = (1 / beta) * Math.exp(
                        (-1.0) * Math.max(0, route.cost(new TimePriority()) - base) / beta);

                map.put(candidate, new Tuple<>(new MatcherTransition(route), transition));

                logger.trace("{} -> {} {} {} {}", predecessor.id(), candidate.id(), base,
                        route.length(), transition);
                count.incrementAndGet();
            }

            transitions.put(predecessor, map);
        }


        sw.stop();

        logger.trace("{} transitions ({} ms)", count.get(), sw.ms());

        return transitions;
    }

    /**
     * Matches a full sequence of samples, {@link MatcherSample} objects and returns state
     * representation of the full matching which is a {@link KState} object.
     *
     * @param samples Sequence of samples, {@link MatcherSample} objects.
     * @param minDistance Minimum distance in meters between subsequent samples as criterion to
     *        match a sample. (Avoids unnecessary matching where samples are more dense than
     *        necessary.)
     * @param minInterval Minimum time interval in milliseconds between subsequent samples as
     *        criterion to match a sample. (Avoids unnecessary matching where samples are more dense
     *        than necessary.)
     * @return State representation of the full matching which is a {@link KState} object.
     */
    public MatcherKState mmatch(List<MatcherSample> samples, double minDistance, int minInterval) {
        Collections.sort(samples, new Comparator<MatcherSample>() {
            @Override
            public int compare(MatcherSample left, MatcherSample right) {
                return (int) (left.time() - right.time());
            }
        });

        MatcherKState state = new MatcherKState();

        for (MatcherSample sample : samples) {
            if (state.sample() != null && (spatial.distance(sample.point(),
                    state.sample().point()) < Math.max(0, minDistance)
                    || (sample.time() - state.sample().time()) < Math.max(0, minInterval))) {
                continue;
            }
            Set<MatcherCandidate> vector = execute(state.vector(), state.sample(), sample);
            state.update(vector, sample);
        }

        return state;
    }
}
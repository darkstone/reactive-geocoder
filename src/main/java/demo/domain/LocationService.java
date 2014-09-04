package demo.domain;

import com.gs.collections.impl.list.mutable.FastList;
import demo.ProcessorConfig;
import demo.geo.GeoNearPredicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.stereotype.Service;
import reactor.core.Environment;
import reactor.rx.Stream;
import reactor.rx.action.Action;
import reactor.rx.action.CallbackAction;
import reactor.rx.spec.Streams;
import reactor.tuple.Tuple;
import reactor.tuple.Tuple2;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static reactor.util.ObjectUtils.nullSafeEquals;

/**
 * @author Jon Brisbin
 */
@Service
public class LocationService {

	private final ConcurrentHashMap<String, Stream<Location>> nearbyStreams  = new ConcurrentHashMap<>();

	private final Environment        env;
	private final LocationRepository locations;
	private final Stream<Location>   locationSaveEvents;
	private final Distance           defaultDistance;

	@Autowired
	public LocationService(Environment env,
	                       LocationRepository locations,
	                       Stream<Location> locationSaveEvents,
	                       ProcessorConfig config) {
		this.env = env;
		this.locations = locations;
		this.locationSaveEvents = locationSaveEvents;
		this.defaultDistance = new Distance(config.getDefaultDistance());

		locations.deleteAll();
	}

	public Action<String, Location> findOne(String id) {
		return Streams.defer(env, env.getDefaultDispatcherFactory().get(), id)
		              .<Location>map(locations::findOne);
	}

	public Stream<Location> create(Location loc) {
		return update(loc, defaultDistance);
	}

	public Stream<Location> update(Location loc, Distance distance) {
		return Streams.defer(env, env.getDefaultDispatcherFactory().get(), loc)

				// persist incoming to MongoDB
				.map(locations::save)

				// broadcast this update to others
				.observe(locationSaveEvents::broadcastNext)

				// create a distance filter using Haversine Formula
				.map(l -> Tuple.of(l, new GeoNearPredicate(l.toPoint(), distance)))

				// refresh cache with nearby Locations and given distance
				.map(tup -> {
					Stream<Location> nearby;
					if (null != (nearby = nearbyStreams.remove(loc.getId()))) {
						nearby.cancel();
					}
					findNearby(tup.getT1(), distance);
					return tup.getT1();
				});
	}

	public Stream<Location> nearby(String locId) {
		return nearbyStreams.get(locId);
	}

	private void findNearby(Location loc, Distance distance) {
		// find nearby Locations
		List<Location> nearbyLocs = locations.findByCoordinatesNear(loc.toPoint(), distance);

		// merge existing nearby Locations with live events
		Streams.merge(env, locationSaveEvents, Streams.defer(nearbyLocs))

				// filter out our own Location
				.filter(nearbyLoc -> !nullSafeEquals(nearbyLoc.getId(), loc.getId()))

				// filter out only Locations within given Distance
				.filter(new GeoNearPredicate(loc.toPoint(), distance))

				// cache this Stream for cancellation later
				.nest().consume(s -> nearbyStreams.put(loc.getId(), s));
	}

}

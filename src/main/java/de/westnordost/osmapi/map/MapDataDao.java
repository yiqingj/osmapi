package de.westnordost.osmapi.map;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.westnordost.osmapi.OsmConnection;
import de.westnordost.osmapi.common.Handler;
import de.westnordost.osmapi.common.IdResponseReader;
import de.westnordost.osmapi.common.XmlWriter;
import de.westnordost.osmapi.common.errors.OsmAuthorizationException;
import de.westnordost.osmapi.common.errors.OsmBadUserInputException;
import de.westnordost.osmapi.common.errors.OsmNotFoundException;
import de.westnordost.osmapi.common.errors.OsmQueryTooBigException;
import de.westnordost.osmapi.map.changes.DiffElement;
import de.westnordost.osmapi.map.changes.MapDataChangesWriter;
import de.westnordost.osmapi.map.changes.MapDataDiffParser;
import de.westnordost.osmapi.map.data.BoundingBox;
import de.westnordost.osmapi.map.data.Element;
import de.westnordost.osmapi.map.data.Node;
import de.westnordost.osmapi.map.data.Relation;
import de.westnordost.osmapi.map.data.Way;
import de.westnordost.osmapi.map.handler.ListOsmElementHandler;
import de.westnordost.osmapi.map.handler.SingleOsmElementHandler;
import de.westnordost.osmapi.map.handler.MapDataHandler;

/** Get and upload changes to map data */
public class MapDataDao
{
	private static final String NODE = "node";
	private static final String WAY = "way";
	private static final String RELATION = "relation";

	private static final String FULL = "full";

	private final OsmConnection osm;
	private final MapDataFactory factory;

	public MapDataDao(OsmConnection osm, MapDataFactory factory)
	{
		this.osm = osm;
		this.factory = factory;
	}
	
	public MapDataDao(OsmConnection osm)
	{
		this(osm, new OsmMapDataFactory());
	}

	/** @see #updateMap(Map, Iterable, Handler)
	 * */
	public long updateMap(String comment, String source, Iterable<Element> elements,
						  Handler<DiffElement> handler)
	{
		Map<String, String> tags = new HashMap<>();
		tags.put("comment", comment);
		tags.put("source", source);
		return updateMap(tags, elements, handler);
	}

	/**
	 * Uploads the data in a new changeset and subscribes the user to it.
	 *
	 * @param tags tags of this changeset. Usually it is comment and source.
	 *              See {@link #updateMap(String, String, Iterable, Handler)}
	 * @param elements elements to upload. No special order required
	 * @param handler handler that processes the server's diffResult response. Optional.
	 *
	 * @throws OsmAuthorizationException if the application does not have permission to edit the
	 * 	                                  map (Permission.MODIFY_MAP)
	 *
	 * @return id of the changeset that was created
	 */
	public long updateMap(Map<String, String> tags, Iterable<Element> elements,
						  Handler<DiffElement> handler)
	{
		tags.put("created_by", osm.getUserAgent());

		long changesetId = openChangeset(tags.entrySet());
		/* the try-finally is not really necessary because the server closes an open changeset after
		   24 hours automatically but it is nicer if we clean up after ourselves in case of error
		   ourselves. */
		try
		{
			uploadDiff(changesetId, elements, handler);
		}
		finally
		{
			closeChangeset(changesetId);
		}

		return changesetId;
	}

	private void uploadDiff(long changesetId, Iterable<Element> elements, Handler<DiffElement> handler)
	{
		MapDataDiffParser parser = null;
		if(handler != null)
		{
			parser = new MapDataDiffParser(handler);
		}

		osm.makeAuthenticatedRequest(
				"changeset/" + changesetId + "/upload", "POST",
				new MapDataChangesWriter(changesetId, elements), parser
		);
	}

	private long openChangeset(final Collection<Map.Entry<String, String>> tags)
	{
		XmlWriter writer = new XmlWriter()
		{
			protected void write() throws IOException
			{
				begin("osm");
				begin("changeset");

				for (Map.Entry<String, String> tag : tags)
				{
					begin("tag");
					attribute("k", tag.getKey());
					attribute("v", tag.getValue());
					end();
				}

				end();
				end();
			}
		};

		return osm.makeAuthenticatedRequest("changeset/create", "PUT", writer, new IdResponseReader());
	}

	private void closeChangeset(long changesetId)
	{
		osm.makeAuthenticatedRequest("changeset/" + changesetId + "/close", "PUT");
	}

	/**
	 * Feeds map data to the given MapDataHandler.
	 *
	 * @param bounds rectangle in which to query map data. May not cross the 180th meridian. This is
	 *               usually limited at 0.25 square degrees. Check the server capabilities.
	 * @param handler map data handler that is fed the map data
	 *
	 * @throws OsmQueryTooBigException if the bounds are is too large
	 * @throws IllegalArgumentException if the bounds cross the 180th meridian.
	 */
	public void getMap(BoundingBox bounds, MapDataHandler handler)
	{
		if(bounds.crosses180thMeridian())
		{
			throw new IllegalArgumentException("bounds may not cross the 180th meridian");
		}

		String request = "map?bbox=" + bounds.getAsLeftBottomRightTopString();

		try
		{
			osm.makeRequest(request, new MapDataParser(handler, factory));
		}
		catch(OsmBadUserInputException e)
		{
			/* we can be more specific here because we checked the validity of all the other
			   parameters already */
			throw new OsmQueryTooBigException(e);
		}
	}

	/** Queries the way with the given id plus all nodes that are in referenced by it.
	 *
	 *  @param id the way's id
	 *  @param handler map data handler that is fed the map data
	 *  @throws OsmNotFoundException if the way with the given id does not exist */
	public void getWayComplete(long id, MapDataHandler handler)
	{
		osm.makeRequest(WAY + "/" + id + "/" + FULL, new MapDataParser(handler, factory));
	}

	/** Queries the relation with the given id plus all it's members and all nodes of ways that are
	 *  members of the relation.
	 *
	 *  @param id the way's id
	 *  @param handler map data handler that is fed the map data
	 *
	 *  @throws OsmNotFoundException if the relation with the given id does not exist*/
	public void getRelationComplete(long id, MapDataHandler handler)
	{
		osm.makeRequest(RELATION + "/" + id + "/" + FULL, new MapDataParser(handler, factory));
	}

	/** @return the node with the given id or null if it does not exist */
	public Node getNode(long id)
	{
		return getOneElement(NODE + "/" + id, Node.class);
	}

	/** @return the way with the given id or null if it does not exist */
	public Way getWay(long id)
	{
		return getOneElement(WAY + "/" + id, Way.class);
	}

	/** @return the relation with the given id or null if it does not exist */
	public Relation getRelation(long id)
	{
		return getOneElement(RELATION + "/" + id, Relation.class);
	}

	private <T extends Element> T getOneElement(String call, Class<T> tClass)
	{
		SingleOsmElementHandler<T> handler = new SingleOsmElementHandler<>(tClass);
		try
		{
			osm.makeRequest(call, new MapDataParser(handler, factory));
		}
		catch(OsmNotFoundException e)
		{
			return null;
		}
		return handler.get();
	}

	/** @param nodeIds a collection of node ids to return.
	 *  @throws OsmNotFoundException if <b>any</b> one of the given nodes does not exist
	 *  @return a list of nodes. */
	public List<Node> getNodes(Collection<Long> nodeIds)
	{
		if(nodeIds.isEmpty()) return Collections.emptyList();
		return getSomeElements(NODE + "s?" + NODE + "s=" + toCommaList(nodeIds), Node.class);
	}

	/** @param wayIds a collection of way ids to return
	 *  @throws OsmNotFoundException if <b>any</b> one of the given ways does not exist
	 *  @return a list of ways. */
	public List<Way> getWays(Collection<Long> wayIds)
	{
		if(wayIds.isEmpty()) return Collections.emptyList();
		return getSomeElements(WAY + "s?" + WAY + "s=" + toCommaList(wayIds), Way.class);
	}

	/** @param relationIds a collection of relation ids to return
	 *  @throws OsmNotFoundException if <b>any</b> one of the given relations does not exist
	 *  @return a list of relations. */
	public List<Relation> getRelations(Collection<Long> relationIds)
	{
		if(relationIds.isEmpty()) return Collections.emptyList();
		return getSomeElements(RELATION + "s?" + RELATION + "s=" + toCommaList(relationIds), Relation.class);
	}

	/** @return all ways that reference the node with the given id. Empty if none. */
	public List<Way> getWaysForNode(long id)
	{
		return getSomeElements(NODE + "/" + id + "/" + WAY + "s", Way.class);
	}

	/** @return all relations that reference the node with the given id. Empty if none. */
	public List<Relation> getRelationsForNode(long id)
	{
		return getSomeElements(NODE + "/" + id + "/" + RELATION + "s", Relation.class);
	}

	/** @return all relations that reference the way with the given id. Empty if none. */
	public List<Relation> getRelationsForWay(long id)
	{
		return getSomeElements(WAY + "/" + id + "/" + RELATION + "s", Relation.class);
	}

	/** @return all relations that reference the relation with the given id. Empty if none. */
	public List<Relation> getRelationsForRelation(long id)
	{
		return getSomeElements(RELATION + "/" + id + "/" + RELATION + "s", Relation.class);
	}

	private static String toCommaList(Iterable<Long> vals)
	{
		StringBuilder result = new StringBuilder();
		boolean first = true;
		for(Long id : vals)
		{
			if(id == null) continue;

			if(first) first = false;
			else      result.append(",");
			result.append(id);
		}
		return result.toString();
	}

	private <T extends Element> List<T> getSomeElements(String call, Class<T> tClass)
	{
		ListOsmElementHandler<T> handler = new ListOsmElementHandler<>(tClass);
		osm.makeRequest(call, new MapDataParser(handler, factory));
		return handler.get();
	}
}

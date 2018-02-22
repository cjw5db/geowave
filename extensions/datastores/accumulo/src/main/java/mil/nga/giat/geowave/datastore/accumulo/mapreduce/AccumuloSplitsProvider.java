/*******************************************************************************
 * Copyright (c) 2013-2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License,
 * Version 2.0 which accompanies this distribution and is available at
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/
package mil.nga.giat.geowave.datastore.accumulo.mapreduce;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;

import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.NumericIndexStrategy;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.adapter.DataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.core.store.adapter.statistics.RowRangeHistogramStatistics;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.operations.DataStoreOperations;
import mil.nga.giat.geowave.core.store.query.DistributableQuery;
import mil.nga.giat.geowave.core.store.util.DataStoreUtils;
import mil.nga.giat.geowave.datastore.accumulo.operations.AccumuloOperations;
import mil.nga.giat.geowave.datastore.accumulo.util.AccumuloUtils;
import mil.nga.giat.geowave.mapreduce.splits.GeoWaveRowRange;
import mil.nga.giat.geowave.mapreduce.splits.IntermediateSplitInfo;
import mil.nga.giat.geowave.mapreduce.splits.RangeLocationPair;
import mil.nga.giat.geowave.mapreduce.splits.SplitInfo;
import mil.nga.giat.geowave.mapreduce.splits.SplitsProvider;
//@formatter:off
/*if[accumulo.api=1.7]
import org.apache.accumulo.core.client.impl.ClientContext;
import org.apache.accumulo.core.client.impl.Credentials;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.TableDeletedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.TableOfflineException;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.accumulo.core.client.impl.TabletLocator;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.NullToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
else[accumulo.api=1.7]*/
import org.apache.accumulo.core.data.TabletId;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.admin.Locations;
/*end[accumulo.api=1.7]*/
//@formatter:on
public class AccumuloSplitsProvider extends
		SplitsProvider
{
	private final static Logger LOGGER = LoggerFactory.getLogger(AccumuloSplitsProvider.class);

	@Override
	protected TreeSet<IntermediateSplitInfo> populateIntermediateSplits(
			final TreeSet<IntermediateSplitInfo> splits,
			final DataStoreOperations operations,
			final PrimaryIndex index,
			final List<DataAdapter<Object>> adapters,
			final Map<PrimaryIndex, RowRangeHistogramStatistics<?>> statsCache,
			final AdapterStore adapterStore,
			final DataStatisticsStore statsStore,
			final Integer maxSplits,
			final DistributableQuery query,
			final String[] authorizations )
			throws IOException {

		AccumuloOperations accumuloOperations = null;
		if (operations instanceof AccumuloOperations) {
			accumuloOperations = (AccumuloOperations) operations;
		}
		else {
			LOGGER.error("AccumuloSplitsProvider requires AccumuloOperations object.");
			return splits;
		}

		if ((query != null) && !query.isSupported(index)) {
			return splits;
		}
		Range fullrange;
		final NumericIndexStrategy indexStrategy = index.getIndexStrategy();
		final int partitionKeyLength = indexStrategy.getPartitionKeyLength();
		try {
			fullrange = toAccumuloRange(
					new GeoWaveRowRange(
							null,
							null,
							null,
							true,
							true),
					partitionKeyLength);
		}
		catch (final Exception e) {
			fullrange = new Range();
			LOGGER.warn(
					"Cannot ascertain the full range of the data",
					e);
		}

		final String tableName = AccumuloUtils.getQualifiedTableName(
				accumuloOperations.getTableNameSpace(),
				index.getId().getString());

		final TreeSet<Range> ranges;
		if (query != null) {
			final List<MultiDimensionalNumericData> indexConstraints = query.getIndexConstraints(index);
			if ((maxSplits != null) && (maxSplits > 0)) {
				ranges = AccumuloUtils.byteArrayRangesToAccumuloRanges(DataStoreUtils.constraintsToQueryRanges(
						indexConstraints,
						indexStrategy,
						maxSplits).getCompositeQueryRanges());
			}
			else {
				ranges = AccumuloUtils.byteArrayRangesToAccumuloRanges(DataStoreUtils.constraintsToQueryRanges(
						indexConstraints,
						indexStrategy,
						-1).getCompositeQueryRanges());
			}
			if (ranges.size() == 1) {
				final Range range = ranges.first();
				if (range.isInfiniteStartKey() || range.isInfiniteStopKey()) {
					ranges.remove(range);
					ranges.add(fullrange.clip(range));
				}
			}
		}
		else {
			ranges = new TreeSet<Range>();
			ranges.add(fullrange);
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Protected range: " + fullrange);
			}
		}
		// get the metadata information for these ranges
		// @formatter:off
		/*if[accumulo.api=1.7]	
		final Map<String, Map<KeyExtent, List<Range>>> tserverBinnedRanges = getBinnedRangesStructure();
		TabletLocator tl;
		try {
			final Instance instance = accumuloOperations.getInstance();
			final String tableId;
			Credentials credentials;
			if (instance instanceof MockInstance) {
				tableId = "";
				// in this case, we will have no password;
				credentials = new Credentials(
						accumuloOperations.getUsername(),
						new NullToken());
			}
			else {
				tableId = Tables.getTableId(
						instance,
						tableName);
				credentials = new Credentials(
						accumuloOperations.getUsername(),
						new PasswordToken(
								accumuloOperations.getPassword()));
			}
			final ClientContext clientContext = new ClientContext(
					instance,credentials,
					new ClientConfiguration());
			tl = getTabletLocator(
					clientContext,
					tableId);

			// its possible that the cache could contain complete, but
			// old information about a tables tablets... so clear it
			tl.invalidateCache();
			final List<Range> rangeList = new ArrayList<Range>(
					ranges);
			while (!binRanges(
					rangeList,
					clientContext,
					tserverBinnedRanges,
					tl)) {
				if (!(instance instanceof MockInstance)) {
					if (!Tables.exists(
							instance,
							tableId)) {
						throw new TableDeletedException(
								tableId);
					}
					if (Tables.getTableState(
							instance,
							tableId) == TableState.OFFLINE) {
						throw new TableOfflineException(
								instance,
								tableId);
					}
				}
				tserverBinnedRanges.clear();
				LOGGER.warn("Unable to locate bins for specified ranges. Retrying.");
				UtilWaitThread.sleep(150);
				tl.invalidateCache();
			}
		}
		catch (final Exception e) {
			throw new IOException(
					e);
		}
		return tserverBinnedRanges;
	}

	public static Range toAccumuloRange(
			final GeoWaveRowRange range,
			final int partitionKeyLength ) {
		if ((range.getPartitionKey() == null) || (range.getPartitionKey().length == 0)) {
			return new Range(
					(range.getStartSortKey() == null) ? null : new Text(
							range.getStartSortKey()),
					range.isStartSortKeyInclusive(),
					(range.getEndSortKey() == null) ? null : new Text(
							range.getEndSortKey()),
					range.isEndSortKeyInclusive());
		}
		else {
			return new Range(
					(range.getStartSortKey() == null) ? null : new Text(
							ArrayUtils.addAll(
									range.getPartitionKey(),
									range.getStartSortKey())),
					range.isStartSortKeyInclusive(),
					(range.getEndSortKey() == null) ? new Text(
							new ByteArrayId(
									range.getPartitionKey()).getNextPrefix()) : new Text(
							ArrayUtils.addAll(
									range.getPartitionKey(),
									range.getEndSortKey())),
					(range.getEndSortKey() != null) && range.isEndSortKeyInclusive());
		}
	}

	public static GeoWaveRowRange fromAccumuloRange(
			final Range range,
			final int partitionKeyLength ) {
		if (partitionKeyLength <= 0) {
			return new GeoWaveRowRange(
					null,
					range.getStartKey() == null ? null : range.getStartKey().getRowData().getBackingArray(),
					range.getEndKey() == null ? null : range.getEndKey().getRowData().getBackingArray(),
					range.isStartKeyInclusive(),
					range.isEndKeyInclusive());
		}
		else {
			byte[] partitionKey;
			boolean partitionKeyDiffers = false;
			if ((range.getStartKey() == null) && (range.getEndKey() == null)) {
				return null;
			}
			else if (range.getStartKey() != null) {
				partitionKey = ArrayUtils.subarray(
						range.getStartKey().getRowData().getBackingArray(),
						0,
						partitionKeyLength);
				if (range.getEndKey() != null) {
					partitionKeyDiffers = !Arrays.equals(
							partitionKey,
							ArrayUtils.subarray(
									range.getEndKey().getRowData().getBackingArray(),
									0,
									partitionKeyLength));
				}
			}
			else {
				partitionKey = ArrayUtils.subarray(
						range.getEndKey().getRowData().getBackingArray(),
						0,
						partitionKeyLength);
			}
			return new GeoWaveRowRange(
					partitionKey,
					range.getStartKey() == null ? null : ArrayUtils.subarray(
							range.getStartKey().getRowData().getBackingArray(),
							partitionKeyLength,
							range.getStartKey().getRowData().getBackingArray().length),
					partitionKeyDiffers ? null : range.getEndKey() == null ? null : ArrayUtils.subarray(
							range.getEndKey().getRowData().getBackingArray(),
							partitionKeyLength,
							range.getEndKey().getRowData().getBackingArray().length),
					range.isStartKeyInclusive(),
					partitionKeyDiffers ? true : range.isEndKeyInclusive());

		else[accumulo.api=1.7]*/
		// @formatter:on
		final HashMap<String, String> hostNameCache = getHostNameCache();
		Locations locations;
		try {
			final Connector conn = accumuloOperations.getConnector();
			locations = conn.tableOperations().locate(
					tableName,
					ranges);
		}
		catch (final Exception e) {
			throw new IOException(
					e);
		}
		for (final Entry<TabletId, List<Range>> tabletIdRanges : locations.groupByTablet().entrySet()) {
			final TabletId tabletId = tabletIdRanges.getKey();
			final String tabletServer = locations.getTabletLocation(tabletId);
			final String ipAddress = tabletServer.split(
					":",
					2)[0];

			String location = hostNameCache.get(ipAddress);
			// HP Fortify "Often Misused: Authentication"
			// These methods are not being used for
			// authentication
			if (location == null) {
				final InetAddress inetAddress = InetAddress.getByName(ipAddress);
				location = inetAddress.getHostName();
				hostNameCache.put(
						ipAddress,
						location);
			}

			final Range tabletRange = tabletId.toRange();
			final Map<ByteArrayId, SplitInfo> splitInfo = new HashMap<ByteArrayId, SplitInfo>();
			final List<RangeLocationPair> rangeList = new ArrayList<RangeLocationPair>();

			for (final Range range : tabletIdRanges.getValue()) {
				final Range clippedRange = tabletRange.clip(range);
				if (!(fullrange.beforeStartKey(clippedRange.getEndKey()) || fullrange.afterEndKey(clippedRange
						.getStartKey()))) {
					final GeoWaveRowRange rowRange = fromAccumuloRange(
							clippedRange,
							partitionKeyLength);
					final double cardinality = getCardinality(
							getHistStats(
									index,
									adapters,
									adapterStore,
									statsStore,
									statsCache,
									authorizations),
							rowRange,
							indexStrategy.getPartitionKeyLength());
					rangeList.add(new RangeLocationPair(
							rowRange,
							location,
							cardinality < 1 ? 1.0 : cardinality));
				}
				else {
					LOGGER.info("Query split outside of range");
				}
				if (LOGGER.isTraceEnabled()) {
					LOGGER.warn("Clipped range: " + rangeList.get(
							rangeList.size() - 1).getRange());
				}
			}
			if (!rangeList.isEmpty()) {
				splitInfo.put(
						index.getId(),
						new SplitInfo(
								index,
								rangeList));
				splits.add(new IntermediateSplitInfo(
						splitInfo,
						this));
			}
		}
		/* end[accumulo.api=1.6] */
		return splits;
	}

	/**
	 * Returns data structure to be filled by binnedRanges Extracted out to
	 * facilitate testing
	 */
	public Map<String, Map<KeyExtent, List<Range>>> getBinnedRangesStructure() {
		final Map<String, Map<KeyExtent, List<Range>>> tserverBinnedRanges = new HashMap<String, Map<KeyExtent, List<Range>>>();
		return tserverBinnedRanges;
	}

	/**
	 * Returns host name cache data structure Extracted out to facilitate
	 * testing
	 */
	public HashMap<String, String> getHostNameCache() {
		final HashMap<String, String> hostNameCache = new HashMap<String, String>();
		return hostNameCache;
	}

	public static Range toAccumuloRange(
			final GeoWaveRowRange range,
			final int partitionKeyLength ) {
		if ((range.getPartitionKey() == null) || (range.getPartitionKey().length == 0)) {
			return new Range(
					(range.getStartSortKey() == null) ? null : new Text(
							range.getStartSortKey()),
					range.isStartSortKeyInclusive(),
					(range.getEndSortKey() == null) ? null : new Text(
							range.getEndSortKey()),
					range.isEndSortKeyInclusive());
		}
		else {
			return new Range(
					(range.getStartSortKey() == null) ? null : new Text(
							ArrayUtils.addAll(
									range.getPartitionKey(),
									range.getStartSortKey())),
					range.isStartSortKeyInclusive(),
					(range.getEndSortKey() == null) ? new Text(
							new ByteArrayId(
									range.getPartitionKey()).getNextPrefix()) : new Text(
							ArrayUtils.addAll(
									range.getPartitionKey(),
									range.getEndSortKey())),
					(range.getEndSortKey() != null) && range.isEndSortKeyInclusive());
		}
	}

	public static GeoWaveRowRange fromAccumuloRange(
			final Range range,
			final int partitionKeyLength ) {
		if (partitionKeyLength <= 0) {
			return new GeoWaveRowRange(
					null,
					range.getStartKey() == null ? null : range.getStartKey().getRowData().getBackingArray(),
					range.getEndKey() == null ? null : range.getEndKey().getRowData().getBackingArray(),
					range.isStartKeyInclusive(),
					range.isEndKeyInclusive());
		}
		else {
			byte[] partitionKey;
			boolean partitionKeyDiffers = false;
			if ((range.getStartKey() == null) && (range.getEndKey() == null)) {
				return null;
			}
			else if (range.getStartKey() != null) {
				partitionKey = ArrayUtils.subarray(
						range.getStartKey().getRowData().getBackingArray(),
						0,
						partitionKeyLength);
				if (range.getEndKey() != null) {
					partitionKeyDiffers = !Arrays.equals(
							partitionKey,
							ArrayUtils.subarray(
									range.getEndKey().getRowData().getBackingArray(),
									0,
									partitionKeyLength));
				}
			}
			else {
				partitionKey = ArrayUtils.subarray(
						range.getEndKey().getRowData().getBackingArray(),
						0,
						partitionKeyLength);
			}
			return new GeoWaveRowRange(
					partitionKey,
					range.getStartKey() == null ? null : ArrayUtils.subarray(
							range.getStartKey().getRowData().getBackingArray(),
							partitionKeyLength,
							range.getStartKey().getRowData().getBackingArray().length),
					partitionKeyDiffers ? null : range.getEndKey() == null ? null : ArrayUtils.subarray(
							range.getEndKey().getRowData().getBackingArray(),
							partitionKeyLength,
							range.getEndKey().getRowData().getBackingArray().length),
					range.isStartKeyInclusive(),
					partitionKeyDiffers ? true : range.isEndKeyInclusive());

		}
	}
	// @formatter:off
	/*if[accumulo.api=1.7]
	protected TabletLocator getTabletLocator(
			final Object clientContextOrInstance,
			final String tableId )
			throws TableNotFoundException {
		TabletLocator tabletLocator = null;

		tabletLocator = TabletLocator.getLocator(
				(ClientContext) clientContextOrInstance,
				new Text(
						tableId));
		return tabletLocator;
	}

	protected static boolean binRanges(
			final List<Range> rangeList,
			final Object clientContextOrCredentials,
			final Map<String, Map<KeyExtent, List<Range>>> tserverBinnedRanges,
			final TabletLocator tabletLocator )
			throws AccumuloException,
			AccumuloSecurityException,
			TableNotFoundException,
			IOException {
		return tabletLocator.binRanges(
				(ClientContext) clientContextOrCredentials,
				rangeList,
				tserverBinnedRanges).isEmpty();
	}
	end[accumulo.api=1.7]*/
	// @formatter:on
}

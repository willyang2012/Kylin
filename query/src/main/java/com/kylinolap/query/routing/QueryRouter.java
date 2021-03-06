/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kylinolap.query.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eigenbase.reltype.RelDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kylinolap.cube.CubeInstance;
import com.kylinolap.cube.CubeManager;
import com.kylinolap.cube.project.ProjectManager;
import com.kylinolap.metadata.model.cube.CubeDesc;
import com.kylinolap.metadata.model.cube.DimensionDesc;
import com.kylinolap.metadata.model.cube.FunctionDesc;
import com.kylinolap.metadata.model.cube.JoinDesc;
import com.kylinolap.metadata.model.cube.ParameterDesc;
import com.kylinolap.metadata.model.cube.TblColRef;
import com.kylinolap.query.relnode.OLAPContext;

/**
 * @author xjiang
 */
public class QueryRouter {

    private static final Logger logger = LoggerFactory.getLogger(QueryRouter.class);

    public static CubeInstance findCube(OLAPContext olapContext) throws CubeNotFoundException {

        CubeInstance bestCube = null;
        // NOTE: since some query has no groups and projections are the superset
        // of groups, we choose projections.
        ProjectManager projectManager = ProjectManager.getInstance(olapContext.olapSchema.getConfig());

        if (olapContext.isSimpleQuery()) {
            // if simple query like "select X from fact table", just return the
            // cube with most dimensions
            // Note that this will only succeed to get best cube if the current
            // simple query is on fact table.
            // Simple query on look up table is handled in
            // OLAPTableScan.genExecFunc
            // In other words, for simple query on lookup tables, bestCube here
            // will be assigned null.
            bestCube = findCubeWithMostDimensions(projectManager, olapContext);
        }

        if (bestCube == null) {
            bestCube = findBestMatchCube(projectManager, olapContext);
        }

        if (bestCube == null) {
            throw new CubeNotFoundException("Can't find cube for fact table " + olapContext.firstTableScan.getCubeTable() + " in project " + olapContext.olapSchema.getProjectName() + " with dimensions " + getDimensionColumns(olapContext) + " and measures " + olapContext.aggregations + ". Also please check whether join types match what defined in Cube.");
        }

        return bestCube;
    }

    private static CubeInstance findCubeWithMostDimensions(ProjectManager projectManager, OLAPContext olapContext) {
        List<CubeInstance> candidates = projectManager.getOnlineCubesByFactTable(olapContext.olapSchema.getProjectName(), olapContext.firstTableScan.getCubeTable());
        if (candidates.isEmpty()) {
            return null;
        }

        CubeInstance cubeWithMostColumns = candidates.get(0);
        for (CubeInstance instance : candidates) {
            int currentDimCount = instance.getDescriptor().listDimensionColumnsIncludingDerived().size();
            int maxDimCount = cubeWithMostColumns.getDescriptor().listDimensionColumnsIncludingDerived().size();

            if ((currentDimCount > maxDimCount) || ((currentDimCount == maxDimCount) && (instance.getCost() < cubeWithMostColumns.getCost())))
                cubeWithMostColumns = instance;
        }
        return cubeWithMostColumns;
    }

    private static void sortByCost(List<CubeInstance> matchCubes) {
        // sort cube candidates, 0) the cost indicator, 1) the lesser header
        // columns the better, 2) the lesser body columns the better
        Collections.sort(matchCubes, new Comparator<CubeInstance>() {
            @Override
            public int compare(CubeInstance c1, CubeInstance c2) {
                int comp = 0;
                comp = c1.getCost() - c2.getCost();
                if (comp != 0) {
                    return comp;
                }

                CubeDesc schema1 = c1.getDescriptor();
                CubeDesc schema2 = c2.getDescriptor();

                comp = schema1.listDimensionColumnsIncludingDerived().size() - schema2.listDimensionColumnsIncludingDerived().size();
                if (comp != 0)
                    return comp;

                comp = schema1.getMeasures().size() - schema2.getMeasures().size();
                return comp;
            }
        });
    }

    private static Collection<TblColRef> getDimensionColumns(OLAPContext olapContext) {
        Collection<TblColRef> dimensionColumns = new HashSet<TblColRef>();
        dimensionColumns.addAll(olapContext.allColumns);
        for (TblColRef measureColumn : olapContext.metricsColumns) {
            dimensionColumns.remove(measureColumn);
        }
        return dimensionColumns;
    }

    static List<CubeInstance> findMatchCubesForTableScanQuery(CubeManager cubeMgr, String factTableName, Collection<TblColRef> dimensionColumns, Collection<FunctionDesc> functions) throws CubeNotFoundException {
        return null;
    }

    static CubeInstance findBestMatchCube(ProjectManager projectManager, OLAPContext olapContext) throws CubeNotFoundException {

        // retrieve members from olapContext
        String factTableName = olapContext.firstTableScan.getCubeTable();
        String projectName = olapContext.olapSchema.getProjectName();
        Collection<TblColRef> dimensionColumns = getDimensionColumns(olapContext);
        Collection<FunctionDesc> functions = olapContext.aggregations;
        Collection<TblColRef> metricsColumns = olapContext.metricsColumns;
        Collection<JoinDesc> joins = olapContext.joins;
        Map<String, RelDataType> rewriteFields = olapContext.rewriteFields;

        // find cubes by table
        List<CubeInstance> candidates = projectManager.getCubesByTable(projectName, factTableName);
        StringBuilder buf = new StringBuilder();
        for (CubeInstance cube : candidates) {
            buf.append(cube.getName());
            buf.append(",");
        }
        logger.info("Find candidates by table " + factTableName + " and project=" + projectName + " : " + buf.toString());

        // match dimensions & aggregations & joins
        Iterator<CubeInstance> it = candidates.iterator();
        List<CubeInstance> backups = new ArrayList<CubeInstance>();

        while (it.hasNext()) {
            CubeInstance cube = it.next();
            boolean isOnline = cube.isReady();

            boolean matchDimensions = isMatchedWithDimensions(dimensionColumns, cube);
            boolean matchAggregation = isMatchedWithAggregations(functions, cube);
            boolean matchJoin = isMatchedWithJoins(joins, cube);

            // Some cubes are not "perfectly" match, but still save them in case
            // of usage
            if (isOnline && matchDimensions && !matchAggregation && matchJoin) {
                // sometimes metrics are indeed dimensions
                // e.g. select min(cal_dt) from ..., where cal_dt is actually a
                // dimension
                if (isWeaklyMatchedWithAggregations(functions, metricsColumns, cube)) {
                    logger.info("Weak matched cube " + cube);
                    backups.add(cube);
                }
            }

            if (!isOnline || !matchDimensions || !matchAggregation || !matchJoin) {
                logger.info("Remove cube " + cube.getName() + " because " + " isOnlne=" + isOnline + ",matchDimensions=" + matchDimensions + ",matchAggregation=" + matchAggregation + ",matchJoin=" + matchJoin);
                it.remove();
            }
        }

        // normal case:
        if (!candidates.isEmpty()) {
            return getCheapestCube(candidates);
        }
        // consider backup
        else if (!backups.isEmpty()) {
            CubeInstance cube = getCheapestCube(backups);
            // Using backup cubes indicates that previous judgment on
            // dimensions/metrics is incorrect
            adjustOLAPContext(dimensionColumns, functions, metricsColumns, cube, rewriteFields, olapContext);
            logger.info("Use weak matched cube " + cube.getName());
            return cube;
        }
        return null;
    }

    private static CubeInstance getCheapestCube(List<CubeInstance> candidates) {
        sortByCost(candidates);
        CubeInstance bestCube = null;
        if (!candidates.isEmpty()) {
            bestCube = candidates.iterator().next();
        }
        return bestCube;
    }

    private static boolean isMatchedWithDimensions(Collection<TblColRef> dimensionColumns, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        boolean matchAgg = cubeDesc.listDimensionColumnsIncludingDerived().containsAll(dimensionColumns);
        return matchAgg;
    }

    private static boolean isMatchedWithAggregations(Collection<FunctionDesc> aggregations, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        boolean matchAgg = cubeDesc.listAllFunctions().containsAll(aggregations);
        return matchAgg;
    }

    private static boolean isMatchedWithJoins(Collection<JoinDesc> joins, CubeInstance cube) throws CubeNotFoundException {
        CubeDesc cubeDesc = cube.getDescriptor();

        List<JoinDesc> cubeJoins = new ArrayList<JoinDesc>(cubeDesc.getDimensions().size());
        for (DimensionDesc d : cubeDesc.getDimensions()) {
            if (d.getJoin() != null) {
                cubeJoins.add(d.getJoin());
            }
        }
        for (JoinDesc j : joins) {
            // optiq engine can't decide which one is fk or pk
            String pTable = j.getPrimaryKeyColumns()[0].getTable();
            String factTable = cubeDesc.getFactTable();
            if (factTable.equals(pTable)) {
                j.swapPKFK();
            }

            // check primary key, all PK column should refer to same tale,
            // the Fact Table of cube.
            // Using first column's table name to check.
            String fTable = j.getForeignKeyColumns()[0].getTable();
            if (!factTable.equals(fTable)) {
                logger.info("Fact Table" + factTable + " not matched in join: " + j + " on cube " + cube.getName());
                return false;
            }

            // The hashcode() function of JoinDesc has been overwritten,
            // which takes into consideration: pk,fk,jointype
            if (!cubeJoins.contains(j)) {
                logger.info("Query joins don't macth on cube " + cube.getName());
                return false;
            }
        }
        return true;
    }

    private static boolean isWeaklyMatchedWithAggregations(Collection<FunctionDesc> aggregations, Collection<TblColRef> metricColumns, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        Collection<FunctionDesc> cubeFuncs = cubeDesc.listAllFunctions();

        boolean matched = true;
        for (FunctionDesc functionDesc : aggregations) {
            if (!cubeFuncs.contains(functionDesc)) {

                if (functionDesc.isCountDistinct())// optiq can not handle
                                                   // distinct count
                    matched = false;

                ParameterDesc param = functionDesc.getParameter();
                if (param != null) {
                    TblColRef col = findTblColByColumnName(metricColumns, param.getValue());
                    if (col == null || !cubeDesc.listDimensionColumnsIncludingDerived().contains(col)) {
                        matched = false;
                    }
                } else {
                    matched = false;
                }
            }
        }
        return matched;
    }

    private static void adjustOLAPContext(Collection<TblColRef> dimensionColumns, Collection<FunctionDesc> aggregations, Collection<TblColRef> metricColumns, CubeInstance cube, Map<String, RelDataType> rewriteFields, OLAPContext olapContext) {
        CubeDesc cubeDesc = cube.getDescriptor();
        Collection<FunctionDesc> cubeFuncs = cubeDesc.listAllFunctions();

        Iterator<FunctionDesc> it = aggregations.iterator();
        while (it.hasNext()) {
            FunctionDesc functionDesc = it.next();
            if (!cubeFuncs.contains(functionDesc)) {
                // try to convert the metric to dimension to see if it works
                TblColRef col = findTblColByColumnName(metricColumns, functionDesc.getParameter().getValue());
                functionDesc.setAppliedOnDimension(true);
                rewriteFields.remove(functionDesc.getRewriteFieldName());
                metricColumns.remove(col);
                dimensionColumns.add(col);
                olapContext.storageContext.mandateColumn(col);
                logger.info("Adjust OLAPContext for " + functionDesc);
            }
        }
    }

    private static TblColRef findTblColByColumnName(Collection<TblColRef> metricColumns, String columnName) {
        for (TblColRef col : metricColumns) {
            String name = col.getName();
            if (name != null && name.equals(columnName))
                return col;
        }
        return null;
    }

}

/******************************************************************************* 
 * * Copyright 2017 Impetus Infotech.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 ******************************************************************************/
package com.impetus.eth.parser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlock.Block;
import org.web3j.protocol.core.methods.response.EthBlock.TransactionResult;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.exceptions.TransactionTimeoutException;
import org.web3j.tx.Transfer;
import org.web3j.utils.Convert;

import com.impetus.blkch.sql.insert.ColumnName;
import com.impetus.blkch.sql.insert.ColumnValue;
import com.impetus.blkch.sql.insert.Insert;
import com.impetus.blkch.sql.parser.LogicalPlan;
import com.impetus.blkch.sql.query.Column;
import com.impetus.blkch.sql.query.Comparator;
import com.impetus.blkch.sql.query.FilterItem;
import com.impetus.blkch.sql.query.FromItem;
import com.impetus.blkch.sql.query.FunctionNode;
import com.impetus.blkch.sql.query.GroupByClause;
import com.impetus.blkch.sql.query.HavingClause;
import com.impetus.blkch.sql.query.IdentifierNode;
import com.impetus.blkch.sql.query.LimitClause;
import com.impetus.blkch.sql.query.LogicalOperation;
import com.impetus.blkch.sql.query.OrderByClause;
import com.impetus.blkch.sql.query.OrderItem;
import com.impetus.blkch.sql.query.OrderingDirection;
import com.impetus.blkch.sql.query.SelectClause;
import com.impetus.blkch.sql.query.SelectItem;
import com.impetus.blkch.sql.query.Table;
import com.impetus.blkch.sql.query.WhereClause;
import com.impetus.eth.jdbc.BlockResultDataHandler;
import com.impetus.eth.jdbc.DataHandler;
import com.impetus.eth.jdbc.DriverConstants;
import com.impetus.eth.jdbc.TransactionResultDataHandler;

public class APIConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(APIConverter.class);

    private LogicalPlan logicalPlan;

    private Web3j web3jClient;
    
    private Properties properties;

    private List<SelectItem> selectItems = new ArrayList<>();

    private List<String> selectColumns = new ArrayList<String>();

    private List<String> extraSelectCols = new ArrayList<String>();

    private Map<String, OrderingDirection> orderList = new HashMap<>();

    private Map<String, String> aliasMapping = new HashMap<>();

    private ArrayList<List<Object>> data;

    private HashMap<String, Integer> columnNamesMap;

    public APIConverter(LogicalPlan logicalPlan, Web3j web3jClient, Properties properties) {
        this.logicalPlan = logicalPlan;
        this.web3jClient = web3jClient;
        this.properties = properties;
        if(logicalPlan.getQuery() != null && logicalPlan.getQuery().hasChildType(SelectClause.class)){
            preprocessSelectClause(logicalPlan);
        }
    }

    private void preprocessSelectClause(LogicalPlan logicalPlan)
    {
        SelectClause selectClause = logicalPlan.getQuery().getChildType(SelectClause.class, 0);
        List<SelectItem> selItems = selectClause.getChildType(SelectItem.class);
        for (SelectItem selItem : selItems) {
            selectItems.add(selItem);
            if (selItem.hasChildType(Column.class)) {
                String colName = selItem.getChildType(Column.class, 0).getChildType(IdentifierNode.class, 0).getValue();
                selectColumns.add(colName);

                if (selItem.hasChildType(IdentifierNode.class)) {
                    String alias = selItem.getChildType(IdentifierNode.class, 0).getValue();

                    if (aliasMapping.containsKey(alias)) {
                        LOGGER.error("Alias " + alias + " is ambiguous");
                        throw new RuntimeException("Alias " + alias + " is ambiguous");
                    } else {
                        aliasMapping.put(alias, colName);
                    }
                }

            } else if (selItem.hasChildType(FunctionNode.class)) {
                String colName;
                Function func = new Function();
                colName = func.createFunctionColName(selItem.getChildType(FunctionNode.class, 0));
                if (selItem.hasChildType(IdentifierNode.class)) {

                    String alias = selItem.getChildType(IdentifierNode.class, 0).getValue();

                    if (aliasMapping.containsKey(alias)) {
                        LOGGER.error("Alias " + alias + " is ambiguous");
                        throw new RuntimeException("Alias " + alias + " is ambiguous");
                    } else {
                        aliasMapping.put(alias, colName);
                    }

                }
            }
        }
    }

    public DataFrame executeQuery() {
        FromItem fromItem = logicalPlan.getQuery().getChildType(FromItem.class, 0);
        Table table = fromItem.getChildType(Table.class, 0);
        String tableName = table.getChildType(IdentifierNode.class, 0).getValue();
        DataHandler dataHandler = null;
        if ("blocks".equalsIgnoreCase(tableName)) {
            dataHandler = new BlockResultDataHandler();
        } else if ("transactions".equalsIgnoreCase(tableName)) {
            dataHandler = new TransactionResultDataHandler();
        } else {
            LOGGER.error("Table : " + tableName + " does not exist. ");
            throw new RuntimeException("Table : " + tableName + " does not exist. ");
        }
        List<?> recordList = getFromTable(tableName);
        DataFrame dataframe;
        List<OrderItem> orderItems = null;
        if (logicalPlan.getQuery().hasChildType(OrderByClause.class)) {
            OrderByClause orderByClause = logicalPlan.getQuery().getChildType(OrderByClause.class, 0);
            orderItems = orderByClause.getChildType(OrderItem.class);
            getorderList(orderItems);
        }
        LimitClause limitClause = null;
        if (logicalPlan.getQuery().hasChildType(LimitClause.class)) {
            limitClause = logicalPlan.getQuery().getChildType(LimitClause.class, 0);
        }

        if (logicalPlan.getQuery().hasChildType(GroupByClause.class)) {
            GroupByClause groupByClause = logicalPlan.getQuery().getChildType(GroupByClause.class, 0);
            List<Column> groupColumns = groupByClause.getChildType(Column.class);
            List<String> groupByCols = groupColumns.stream()
                    .map(col -> col.getChildType(IdentifierNode.class, 0).getValue()).collect(Collectors.toList());
            if (!aliasMapping.isEmpty()) {
                groupByCols = Utils.getActualGroupByCols(groupByCols, aliasMapping);
            }
            Utils.verifyGroupedColumns(selectColumns, groupByCols, aliasMapping);

            data = dataHandler.convertGroupedDataToObjArray(recordList, selectItems, groupByCols);
            columnNamesMap = dataHandler.getColumnNamesMap();
            dataframe = new DataFrame(data, columnNamesMap, aliasMapping, tableName);

            dataframe = performHaving(dataframe, groupByCols);

            if (!(orderItems == null)) {
                if (extraSelectCols.isEmpty()) {
                    dataframe = dataframe.order(orderList, null);
                } else {
                    throw new RuntimeException("order by column(s) "
                            + extraSelectCols.toString().replace("[", "").replace("]", "") + " are not valid to use ");
                }
            }

            if (limitClause == null) {
                return dataframe;
            } else {
                return dataframe.limit(limitClause);
            }
        }
        data = dataHandler.convertToObjArray(recordList, selectItems, extraSelectCols);
        columnNamesMap = dataHandler.getColumnNamesMap();
        dataframe = new DataFrame(data, columnNamesMap, aliasMapping, tableName);
        if (!(orderItems == null)) {

            dataframe = dataframe.order(orderList, extraSelectCols);
        }
        if (limitClause == null) {
            return dataframe;
        } else {
            return dataframe.limit(limitClause);
        }
    }

    public <T> List<?> getFromTable(String tableName) {
        List<T> recordList = new ArrayList<>();

        if ("blocks".equalsIgnoreCase(tableName) || "transactions".equalsIgnoreCase(tableName)) {
            if (logicalPlan.getQuery().hasChildType(WhereClause.class)) {
                recordList.addAll((Collection<? extends T>) executeWithWhereClause(tableName));
            } else {
                LOGGER.error("Query without where clause not supported.");
                throw new RuntimeException("Query without where clause not supported.");
            }
        } else {
            LOGGER.error("Unidentified table " + tableName);
            throw new RuntimeException("Unidentified table " + tableName);
        }
        return recordList;
    }

    public List<?> executeWithWhereClause(String tableName) {
        WhereClause whereClause = logicalPlan.getQuery().getChildType(WhereClause.class, 0);
        if (whereClause.hasChildType(FilterItem.class)) {
            FilterItem filterItem = whereClause.getChildType(FilterItem.class, 0);
            return executeSingleWhereClause(tableName, filterItem);
        } else {
            return executeMultipleWhereClause(tableName, whereClause);

        }
    }

    public List<?> executeSingleWhereClause(String tableName, FilterItem filterItem) {
        String filterColumn = null;

        filterColumn = filterItem.getChildType(Column.class, 0).getChildType(IdentifierNode.class,0).getValue();
        // TODO Implement comparator function to take other operators(for now
        // only =)
        String value = filterItem.getChildType(IdentifierNode.class, 0).getValue();
        List dataList = new ArrayList<>();

        //
        try {
            if ("blocks".equalsIgnoreCase(tableName)) {
                Block block = new Block();

                if ("blocknumber".equalsIgnoreCase(filterColumn)) {
                    block = getBlock(value);
                    dataList.add(block);
                } else if ("blockHash".equalsIgnoreCase(filterColumn)) {

                    block = getBlockByHash(value.replace("'", ""));
                    dataList.add(block);

                } else {
                    LOGGER.error("Column " + filterColumn + " is not filterable");
                    throw new RuntimeException("Column " + filterColumn
                            + " is not filterable/ Doesn't exist in the table");
                }

            } else if ("transactions".equalsIgnoreCase(tableName)) {

                if ("blocknumber".equalsIgnoreCase(filterColumn)) {
                    List<TransactionResult> transactionResult = getTransactions(value.replace("'", ""));
                    dataList = transactionResult;
                } else if ("hash".equalsIgnoreCase(filterColumn)) {
                    Transaction transInfo = getTransactionByHash(value.replace("'", ""));
                    dataList.add(transInfo);

                } else {
                    LOGGER.error("Column " + filterColumn + " is not filterable/ Doesn't exist in the table");
                    throw new RuntimeException("Column " + filterColumn + " is not filterable");
                }

            }
        } catch (Exception e) {

            throw new RuntimeException(e.getMessage());
        }
        return dataList;
    }

    public List<?> executeMultipleWhereClause(String tableName, WhereClause whereClause) {
        LogicalOperation operation = whereClause.getChildType(LogicalOperation.class, 0);
        return executeLogicalOperation(tableName, operation);
    }

    public <T> List<?> executeLogicalOperation(String tableName, LogicalOperation operation) {
        if (operation.getChildNodes().size() != 2) {
            throw new RuntimeException("Logical operation should have two boolean expressions");
        }
        List<?> firstBlock, secondBlock;
        if (operation.getChildNode(0) instanceof LogicalOperation) {
            firstBlock = executeLogicalOperation(tableName, (LogicalOperation) operation.getChildNode(0));
        } else {
            FilterItem filterItem = (FilterItem) operation.getChildNode(0);
            firstBlock = executeSingleWhereClause(tableName, filterItem);
        }
        if (operation.getChildNode(1) instanceof LogicalOperation) {
            secondBlock = executeLogicalOperation(tableName, (LogicalOperation) operation.getChildNode(1));
        } else {
            FilterItem filterItem = (FilterItem) operation.getChildNode(1);
            secondBlock = executeSingleWhereClause(tableName, filterItem);
        }
        List<T> recordList = new ArrayList<>();
        Map<String, Object> firstBlockMap = new HashMap<>(), secondBlockMap = new HashMap<>();

        if ("transactions".equalsIgnoreCase(tableName)) {
            for (Object transInfo : firstBlock) {

                firstBlockMap.put(((Transaction) transInfo).getHash(), transInfo);
            }
            for (Object transInfo : secondBlock) {
                secondBlockMap.put(((Transaction) transInfo).getHash(), transInfo);
            }
        } else if ("blocks".equalsIgnoreCase(tableName)) {
            for (Object blockInfo : firstBlock) {
                firstBlockMap.put(((Block) blockInfo).getHash(), blockInfo);
            }
            for (Object blockInfo : secondBlock) {
                secondBlockMap.put(((Block) blockInfo).getHash(), blockInfo);
            }
        }

        if (operation.isAnd()) {
            for (String recordHash : firstBlockMap.keySet()) {
                if (secondBlockMap.containsKey(recordHash)) {
                    recordList.add((T) secondBlockMap.get(recordHash));
                }
            }
        } else {
            recordList.addAll((Collection<? extends T>) firstBlock);
            for (String recordHash : secondBlockMap.keySet()) {
                if (!firstBlockMap.containsKey(recordHash)) {
                    recordList.add((T) secondBlockMap.get(recordHash));
                }
            }
        }
        return recordList;
    }

    private List<TransactionResult> getTransactions(String blockNumber) throws IOException {
        LOGGER.info("Getting details of transactions stored in block - " + blockNumber);
        EthBlock block = web3jClient.ethGetBlockByNumber(DefaultBlockParameter.valueOf(new BigInteger(blockNumber)),
                true).send();

        return block.getBlock().getTransactions();
    }

    private Block getBlock(String blockNumber) throws IOException {
        LOGGER.info("Getting block - " + blockNumber + " Information ");
        EthBlock block = web3jClient.ethGetBlockByNumber(DefaultBlockParameter.valueOf(new BigInteger(blockNumber)),
                true).send();
        return block.getBlock();
    }

    private Block getBlockByHash(String blockHash) throws IOException {
        LOGGER.info("Getting  information of block with hash - " + blockHash);
        EthBlock block = web3jClient.ethGetBlockByHash(blockHash, true).send();
        return block.getBlock();
    }

    private Transaction getTransactionByHash(String transactionHash) throws IOException {
        LOGGER.info("Getting information of Transaction by hash - " + transactionHash);

        Transaction transaction = web3jClient.ethGetTransactionByHash(transactionHash).send().getResult();
        return transaction;
    }

    private Transaction getTransactionByBlockHashAndIndex(String blockHash, BigInteger transactionIndex)
            throws IOException {
        LOGGER.info("Getting information of Transaction by blockhash - " + blockHash + " and transactionIndex"
                + transactionIndex);

        Transaction transaction = web3jClient.ethGetTransactionByBlockHashAndIndex(blockHash, transactionIndex).send()
                .getResult();
        return transaction;
    }
    
    private Object insertTransaction(String toAddress, String value, String unit, boolean syncRequest)
            throws IOException, CipherException, InterruptedException, TransactionTimeoutException, ExecutionException
    {
        toAddress = toAddress.replaceAll("'", "");
        value = value.replaceAll("'", "");
        unit = unit.replaceAll("'", "").toUpperCase();
        Object val;
        try
        {
            if (value.indexOf('.') > 0)
            {
                val = Double.parseDouble(value);
            }
            else
            {
                val = Long.parseLong(value);
            }
        }
        catch (NumberFormatException e)
        {
            LOGGER.error("Exception while parsing value", e);
            throw new RuntimeException("Exception while parsing value", e);
        }
        Credentials credentials = WalletUtils.loadCredentials(
                properties.getProperty(DriverConstants.KEYSTORE_PASSWORD),
                properties.getProperty(DriverConstants.KEYSTORE_PATH));
        Object transactionReceipt;
        if (syncRequest)
        {
            transactionReceipt = Transfer.sendFunds(web3jClient, credentials, toAddress,
                    BigDecimal.valueOf((val instanceof Long) ? (Long) val : (Double) val), Convert.Unit.valueOf(unit));
        }
        else
        {
            transactionReceipt = Transfer.sendFundsAsync(web3jClient, credentials,
                    toAddress, BigDecimal.valueOf((val instanceof Long) ? (Long) val : (Double) val),
                    Convert.Unit.valueOf(unit));
        }

       
        return transactionReceipt;
    }

    private DataFrame performHaving(DataFrame dataframe, List<String> groupByCols) {
        if (logicalPlan.getQuery().hasChildType(HavingClause.class)) {
            HavingClause havingClause = logicalPlan.getQuery().getChildType(HavingClause.class, 0);
            if (havingClause.hasChildType(FilterItem.class)) {
                dataframe = executeSingleHavingClause(havingClause.getChildType(FilterItem.class, 0), dataframe,
                        groupByCols);
            } else {
                dataframe = executeMultipleHavingClause(havingClause.getChildType(LogicalOperation.class, 0),
                        dataframe, groupByCols);
            }
        }
        return dataframe;
    }

    private DataFrame executeSingleHavingClause(FilterItem filterItem, DataFrame dataframe, List<String> groupByCols) {
        String column = filterItem.getChildType(Column.class, 0).getChildType(IdentifierNode.class, 0).getValue();
        Comparator comparator = filterItem.getChildType(Comparator.class, 0);
        String value = filterItem.getChildType(IdentifierNode.class, 0).getValue().replace("'", "");
        int groupIdx = -1;
        boolean invalidFilterCol = false;
        Set<String> columns = dataframe.getColumnNamesMap().keySet();
        if (columns.contains(column)) {
            if (!columnNamesMap.containsKey(column)) {
                invalidFilterCol = true;
            } else {
                groupIdx = columnNamesMap.get(column);
            }
        } else if (aliasMapping.containsKey(column)) {
            if (!columnNamesMap.containsKey(aliasMapping.get(column))) {
                invalidFilterCol = true;
            } else {
                groupIdx = columnNamesMap.get(aliasMapping.get(column));
            }
        } else {
            invalidFilterCol = true;
        }
        if (invalidFilterCol || (groupIdx == -1)) {
            throw new RuntimeException("Column " + column + " must appear in Select clause");
        }
        final int groupIndex = groupIdx;
        List<List<Object>> filterData = dataframe.getData().stream().filter(entry -> {
            Object cellValue = entry.get(groupIndex);
            if (cellValue == null) {
                return false;
            }
            if (cellValue instanceof Number) {
                Double cell = Double.parseDouble(cellValue.toString());
                Double doubleValue = Double.parseDouble(value);
                if (comparator.isEQ()) {
                    return cell.equals(doubleValue);
                } else if (comparator.isGT()) {
                    return cell > doubleValue;
                } else if (comparator.isGTE()) {
                    return cell >= doubleValue;
                } else if (comparator.isLT()) {
                    return cell < doubleValue;
                } else if (comparator.isLTE()) {
                    return cell <= doubleValue;
                } else {
                    return !cell.equals(doubleValue);
                }
            } else {
                int comparisionValue = cellValue.toString().compareTo(value);
                if (comparator.isEQ()) {
                    return comparisionValue == 0;
                } else if (comparator.isGT()) {
                    return comparisionValue > 0;
                } else if (comparator.isGTE()) {
                    return comparisionValue >= 0;
                } else if (comparator.isLT()) {
                    return comparisionValue < 0;
                } else if (comparator.isLTE()) {
                    return comparisionValue <= 0;
                } else {
                    return comparisionValue != 0;
                }
            }
        }).collect(Collectors.toList());

        return new DataFrame(filterData, columnNamesMap, aliasMapping, dataframe.getTable());
    }

    private DataFrame executeMultipleHavingClause(LogicalOperation operation, DataFrame dataframe,
            List<String> groupByCols) {
        if (operation.getChildNodes().size() != 2) {
            throw new RuntimeException("Logical operation should have two boolean expressions");
        }
        DataFrame firstOut, secondOut = new DataFrame(new ArrayList<List<Object>>(), columnNamesMap, aliasMapping,
                dataframe.getTable());
        List<List<Object>> returnData = new ArrayList<List<Object>>();
        if (operation.getChildNode(0) instanceof LogicalOperation) {
            firstOut = executeMultipleHavingClause((LogicalOperation) operation.getChildNode(0), dataframe, groupByCols);
        } else {
            FilterItem filterItem = (FilterItem) operation.getChildNode(0);
            firstOut = executeSingleHavingClause(filterItem, dataframe, groupByCols);
        }
        if (operation.getChildNode(1) instanceof LogicalOperation) {
            secondOut = executeMultipleHavingClause((LogicalOperation) operation.getChildNode(1), dataframe,
                    groupByCols);
        } else {
            FilterItem filterItem = (FilterItem) operation.getChildNode(1);
            secondOut = executeSingleHavingClause(filterItem, dataframe, groupByCols);
        }
        if (operation.isAnd()) {
            for (List<Object> key : firstOut.getData()) {
                if (secondOut.getData().contains(key)) {
                    returnData.add(key);
                }
            }
        } else {
            returnData.addAll(firstOut.getData());
            for (List<Object> key : secondOut.getData()) {
                if (!returnData.contains(key)) {
                    returnData.add(key);
                }
            }
        }
        return new DataFrame(returnData, columnNamesMap, aliasMapping, dataframe.getTable());
    }

    public void getorderList(List<OrderItem> orderItems) {

        for (OrderItem orderItem : orderItems) {
            OrderingDirection direction = orderItem.getChildType(OrderingDirection.class, 0);
            String col = orderItem.getChildType(Column.class, 0).getChildType(IdentifierNode.class, 0).getValue();
            orderList.put(col, direction);

            if (!selectColumns.contains(col) && !aliasMapping.containsKey(col))
                extraSelectCols.add(col);

        }
    }

    public Boolean execute()
    {
            try
            {
                executeAndReturn();
            }
            catch (Exception e)
            {
                return false;
            }
        return true;
    }
    
    
    public Object executeAndReturn()
    {
        // get values from logical plan and pass it to insertTransaction method
        Insert insert = logicalPlan.getInsert();
        ColumnName names = insert.getChildType(ColumnName.class).get(0);
        ColumnValue values = insert.getChildType(ColumnValue.class).get(0);
        Map<String, String> namesMap = new HashMap<String, String>();
        namesMap.put(names.getChildType(IdentifierNode.class, 0).getValue(),
                values.getChildType(IdentifierNode.class, 0).getValue());
        namesMap.put(names.getChildType(IdentifierNode.class, 1).getValue(),
                values.getChildType(IdentifierNode.class, 1).getValue());
        namesMap.put(names.getChildType(IdentifierNode.class, 2).getValue(),
                values.getChildType(IdentifierNode.class, 2).getValue());
        if (names.getChildType(IdentifierNode.class, 3) != null
                && values.getChildType(IdentifierNode.class, 3) != null)
        {
            namesMap.put(names.getChildType(IdentifierNode.class, 3).getValue(),
                    values.getChildType(IdentifierNode.class, 3).getValue());
        }
        boolean async = namesMap.get("async") == null ? true : Boolean.parseBoolean(namesMap.get("async"));
        Object result = null;
        try
        {
            result = insertTransaction(namesMap.get("toAddress"), namesMap.get("value"), namesMap.get("unit"), !async);
        }
        catch (IOException | CipherException | InterruptedException | TransactionTimeoutException | ExecutionException e)
        {
            e.printStackTrace();
            throw new RuntimeException("Error while executing query", e);
        }
        return result;
    }
    
}

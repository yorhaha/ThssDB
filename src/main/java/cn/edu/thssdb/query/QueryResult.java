package cn.edu.thssdb.query;

import cn.edu.thssdb.parser.statement.*;
import cn.edu.thssdb.schema.Column;
import cn.edu.thssdb.schema.Row;
import cn.edu.thssdb.schema.Table;
import cn.edu.thssdb.utils.Cell;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

public class QueryResult {

    private List<MetaInfo> metaInfos;
    private List<Table> tables;
    private String msg;
    private List<Integer> index;
    private List<Cell> attrs;
    ArrayList<Row> combinedRowList;
    ArrayList<String> columnFullName;

    public QueryResult() {
    }

    public QueryResult(QueryTable[] queryTables) {
        // TODO
        this.index = new ArrayList<>();
        this.attrs = new ArrayList<>();
    }

    public QueryResult(ArrayList<Table> tables) {
        this.tables = new ArrayList<>();
        this.tables.addAll(tables);
        this.metaInfos = new ArrayList<>();
        for(Table table: tables) {
            this.metaInfos.add(new MetaInfo(table.getTableName(), table.columns));
        }
    }

    public QueryResult(ArrayList<Table> tables, Condition onCondition) throws Exception {
        this.tables = new ArrayList<>();
        this.tables.addAll(tables);
        this.metaInfos = new ArrayList<>();
        for(Table table: tables) {
            this.metaInfos.add(new MetaInfo(table.getTableName(), table.columns));
        }

        combinedRowList = new ArrayList<>();
        boolean reverse=false;
        if(onCondition!=null) {
            if (((ColumnFullName) onCondition.expressionLeft.comparerLeft).tableName.equals(metaInfos.get(0).getTableName()) &&
                    ((ColumnFullName) onCondition.expressionRight.comparerLeft).tableName.equals(metaInfos.get(1).getTableName())) {
                reverse = false;
            } else if (((ColumnFullName) onCondition.expressionLeft.comparerLeft).tableName.equals(metaInfos.get(1).getTableName()) &&
                    ((ColumnFullName) onCondition.expressionRight.comparerLeft).tableName.equals(metaInfos.get(0).getTableName())) {
                reverse = true;
            } else {
                throw new Exception("on condition is wrong");
            }
        }


        for(Row rowLeft: tables.get(0)) {
            for(Row rowRight: tables.get(1)) {
                Row rowCombined = combineRow(rowLeft, rowRight, onCondition, reverse);
                if(rowCombined!=null) {
                    combinedRowList.add(rowCombined);
                }
            }
        }

        columnFullName = new ArrayList<>();
        for(Column col : metaInfos.get(0).getColumns()) {
            columnFullName.add(tables.get(0).getTableName() + "." + col.getName());
        }
        for(Column col : metaInfos.get(1).getColumns()) {
            columnFullName.add(tables.get(1).getTableName() + "." + col.getName());
        }

    }

    public QueryResult(Table table) {
        this.tables = new ArrayList<>();
        this.tables.add(table);
        this.metaInfos = new ArrayList<MetaInfo>();
        this.metaInfos.add(new MetaInfo(table.getTableName(), table.columns));
    }

    public Row combineRow(Row rowLeft, Row rowRight, Condition onCondition, boolean reversed) throws Exception {
        MetaInfo metaInfo1, metaInfo2;
        if(!reversed) {
            metaInfo1 = metaInfos.get(0);
            metaInfo2 = metaInfos.get(1);
        }
        else {
            metaInfo1 = metaInfos.get(1);
            metaInfo2 = metaInfos.get(0);
        }
        if(onCondition==null) {
            Row row = new Row(rowLeft.getEntries());
            row.appendEntries(rowRight.getEntries());
            return row;
        }
        else {
            Comparable valueLeft = getExpressionValue(onCondition.expressionLeft, metaInfo1, rowLeft);
            Comparable valueRight = getExpressionValue(onCondition.expressionRight, metaInfo2, rowRight);
            if(satisfy(onCondition.op, valueLeft, valueRight)) {
                Row row = new Row(rowLeft.getEntries());
                row.appendEntries(rowRight.getEntries());
                return row;
            }
            return null;
        }

    }

    public Row generateQueryRecord(Row row) {
        // TODO
        return null;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public ArrayList<Row> getRowFromQuery(Condition condition) throws Exception {
        return filterRows(condition, tables.get(0));
    }

    public String selectQuery(ArrayList<ColumnFullName> resultColumnNameList, Condition condition) throws Exception {
        if(this.tables.size()>1) {
            return selectJoinQuery(resultColumnNameList, condition);
        }

        ArrayList<Integer> inxList = new ArrayList<>();
        // star
        if(resultColumnNameList.size()==1 && resultColumnNameList.get(0).columnName==null)
        {
            for(int i=0;i<tables.get(0).columns.size();i++) {
                inxList.add(i);
            }
        }
        else {
            for(ColumnFullName colFullName: resultColumnNameList) {
                int idx = tables.get(0).indexOfColumn(colFullName.columnName);
                if(idx==-1) throw new Exception("No such column name in the table: "+colFullName.columnName);
                inxList.add(idx);
            }
        }

        ArrayList<Row> selectedRows = filterRows(condition, tables.get(0));
        StringJoiner joiner = new StringJoiner("\n");
        StringJoiner colNames = new StringJoiner(" | ");
        if(resultColumnNameList.size()==1 && resultColumnNameList.get(0).columnName==null) {
            for(Column col: tables.get(0).columns) {
                colNames.add(col.getName());
            }
        }
        else {
            for(ColumnFullName colFullName: resultColumnNameList) {
                colNames.add(colFullName.columnName);
            }
        }
        joiner.add(colNames.toString());
        for (Row row: selectedRows) {
            StringJoiner values = new StringJoiner(" | ");
            for(int idx: inxList) {
                values.add(row.getEntries().get(idx).toString());
            }
            joiner.add(values.toString());
        }
        return joiner.toString();
    }

    public String selectJoinQuery(ArrayList<ColumnFullName> resultColumnNameList, Condition condition) throws Exception {
        // 查询时有多个表的情况
        ArrayList<Integer> inxList = new ArrayList<>();
        // star
        if(resultColumnNameList.size()==1 && resultColumnNameList.get(0).columnName==null)
        {
            for(int i=0;i<columnFullName.size();i++) {
                inxList.add(i);
            }
        }
        else {
            for(ColumnFullName colFullName: resultColumnNameList) {
                int idx = columnFullName.indexOf(colFullName.tableName + "." + colFullName.columnName);
                if(idx==-1) throw new Exception("No such column name in the table: " + colFullName.columnName);
                inxList.add(idx);
            }
        }

        ArrayList<Row> selectedRows = filterRows(condition, combinedRowList);
        // selectedRows = combinedRowList;

        StringJoiner joiner = new StringJoiner("\n");
        StringJoiner colNames = new StringJoiner(" | ");
        if(resultColumnNameList.size()==1 && resultColumnNameList.get(0).columnName==null) {
            for(String col: columnFullName) {
                colNames.add(col);
            }
        }
        else {
            for(ColumnFullName colFullName: resultColumnNameList) {
                colNames.add(colFullName.tableName + "." + colFullName.columnName);
            }
        }
        joiner.add(colNames.toString());
        for (Row row: selectedRows) {
            StringJoiner values = new StringJoiner(" | ");
            for(int idx: inxList) {
                values.add(row.getEntries().get(idx).toString());
            }
            joiner.add(values.toString());
        }
        return joiner.toString();


    }

    private ArrayList<Row> filterRows(Condition condition, Table onlyTable) throws Exception {
        ArrayList<Row> rows = new ArrayList<>();
        onlyTable.readLock();
        for (Row row : onlyTable) {
            if (condition == null || satisfyConditions(row, condition)) {
                rows.add(row);
            }
        }
        onlyTable.readUnlock();
        return rows;
    }

    private ArrayList<Row> filterRows(Condition condition, ArrayList<Row> combinedRowList) throws Exception {
        ArrayList<Row> rows = new ArrayList<>();
        for (Row row : combinedRowList) {
            if (condition == null || satisfyConditions(row, condition)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean satisfyConditions(Row row, Condition condition) throws Exception {
        String op = condition.op;
        if(metaInfos.size() == 1) { // 左属性 右值
            Comparable valueLeft = getExpressionValue(condition.expressionLeft, metaInfos.get(0),   row); // attrName
            Comparable valueRight = getExpressionValue(condition.expressionRight, null,  row); // attrValue
            return satisfy(op, valueLeft, valueRight);
        }
        else { // 可能均为属性
            Comparable valueLeft = getExpressionValue(condition.expressionLeft, null,  row); // attrName
            Comparable valueRight = getExpressionValue(condition.expressionRight, null,  row); // attrValue
            return satisfy(op, valueLeft, valueRight);
        }


    }

    private boolean satisfy(String op, Comparable valueLeft, Comparable valueRight){
        // 字符比较或者数字比较
        int compareResult;
        if (valueLeft instanceof String) {
            compareResult = valueLeft.toString().compareTo(valueRight.toString());
        } else {
            compareResult = Double.valueOf(valueLeft.toString()).compareTo(Double.valueOf(valueRight.toString()));
        }

        switch (op) {
            case "=":
                return compareResult == 0;
            case "<>":
                return compareResult != 0;
            case "<":
                return compareResult < 0;
            case ">":
                return compareResult > 0;
            case "<=":
                return compareResult <= 0;
            case ">=":
                return compareResult >= 0;
            default:
                return false;
        }
    }

    // TODO: 还不支持nested表达式
    Comparable getExpressionValue(Expression expression, MetaInfo metainfo, Row row) throws Exception {
        if (expression.op != null) {
            throw new Exception("nested expression not supported yet");
        }
        Comparer comparer = expression.comparerLeft;

        if (comparer.get_type().equals(Comparer.Type.COLUMN_FULL_NAME)) {
            ColumnFullName fullName = (ColumnFullName) comparer;
            int index = -1;
            if(metainfo!=null) index = metainfo.columnFind(fullName.columnName);
            else {
                index = columnFullName.indexOf(fullName.tableName + "." + fullName.columnName);
            }
            if (index == -1) {
                throw new Exception("Column does not exist.");
            }
            return row.getEntries().get(index).value;
        } else {
            return ((LiteralValue) comparer).value;
        }
    }

}

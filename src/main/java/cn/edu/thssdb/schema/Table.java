package cn.edu.thssdb.schema;

import cn.edu.thssdb.exception.DuplicateKeyException;
import cn.edu.thssdb.exception.KeyNotExistException;
import cn.edu.thssdb.exception.LockWaitTimeoutException;
import cn.edu.thssdb.index.BPlusTree;
import cn.edu.thssdb.type.ColumnType;
import cn.edu.thssdb.utils.Global;
import cn.edu.thssdb.utils.Pair;
import cn.edu.thssdb.utils.StringHelper;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Table implements Iterable<Row>, Serializable {
    private static final long serialVersionUID = -5809702518272943999L;

    public ArrayList<Column> columns;
    public BPlusTree<Entry, Row> index;
    ReentrantReadWriteLock lock;
    private String databaseName;
    private String tableName;
    private int primaryIndex;

    private Long xLockOwner = null;
    private List<Long> sLockOwner = new ArrayList<>();

    public Table(String databaseName, String tableName, Column[] columns) {
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.columns = new ArrayList<>();
        if (columns != null)
            this.columns.addAll(Arrays.asList(columns));
        this.index = new BPlusTree<>();
        this.lock = new ReentrantReadWriteLock();

        // init primary index
        this.primaryIndex = -1;
        int len = this.columns.size();
        for (int i = 0; i < len; i++) {
            if (this.columns.get(i).isPrimary()) {
                this.primaryIndex = i;
                break;
            }
        }
        if (this.primaryIndex == -1 && len > 0) {
            // no primary, set one
            this.columns.get(0).setPrimary();
            this.primaryIndex = 0;
        }
    }

    public void removeXLock(Long sessionId) {
        if (xLockOwner.equals(sessionId)) {
            xLockOwner = null;
        }
    }

    public void removeSLock(Long sessionId) {
        sLockOwner.remove(sessionId);
    }

    public boolean getXLock(Long sessionId) {
        if (xLockOwner != null) {
            if (!xLockOwner.equals(sessionId))
                return false;
            else {
                if (!(sLockOwner.size() == 1 && sLockOwner.get(0).equals(sessionId)))
                    return false;
                else {
                    sLockOwner.clear();
                    return true;
                }
            }
        } else {
            if (sLockOwner.size() == 0) {
                xLockOwner = sessionId;
                return true;
            }
            if (!(sLockOwner.size() == 1 && sLockOwner.get(0).equals(sessionId)))
                return false;
            else {
                sLockOwner.clear();
                xLockOwner = sessionId;
                return true;
            }
        }
    }

    public void getXLockWithWait(Long sessionId) throws InterruptedException {
        if (!getXLock(sessionId)) {
            int i;
            for (i = 0; i < Global.LOCK_TRY_TIME; i++) {
                Thread.sleep(Global.LOCK_WAIT_INTERVAL);
                if (getXLock(sessionId))
                    break;
            }
            if (i == Global.LOCK_TRY_TIME)
                throw new LockWaitTimeoutException(tableName);
        }
    }

    public boolean getSLock(Long sessionId) {
        if (xLockOwner == null) {
            if (!sLockOwner.contains(sessionId))
                sLockOwner.add(sessionId);
            return true;
        }
        if (xLockOwner.equals(sessionId))
            return true;
        return false;
    }

    public void getSLockWithWait(Long sessionId) throws InterruptedException {
        if (!getSLock(sessionId)) {
            int i;
            for (i = 0; i < Global.LOCK_TRY_TIME; i++) {
                Thread.sleep(Global.LOCK_WAIT_INTERVAL);
                if (getSLock(sessionId))
                    break;
            }
            if (i == Global.LOCK_TRY_TIME)
                throw new LockWaitTimeoutException(tableName);
        }
    }


    public boolean persist() {
        try {
            lock.readLock().lock();
            return serialize();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void recover() {
        try {
            lock.writeLock().lock();
            ArrayList<Row> rows = deserialize();
            if (!rows.isEmpty()) {
                for (Row row : rows) {
                    index.put(row.getEntries().get(primaryIndex), row);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void insert(Row row) throws DuplicateKeyException {
        Entry primary_key = row.getEntries().get(primaryIndex);
        boolean has_exist;
        try {
            lock.readLock().lock();
            has_exist = index.contains(primary_key);
        } finally {
            lock.readLock().unlock();
        }
        if (!has_exist) {
            try {
                lock.writeLock().lock();
                index.put(primary_key, row);
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            throw new DuplicateKeyException(primary_key);
        }
    }

    public Row insert(String[] columnNames, String[] values) throws Exception {
        if (columnNames == null || values == null || columnNames.length == 0 || values.length == 0) {
            throw new Exception("columnNames and values is empty");
        }
        if (columnNames.length != values.length) {
            throw new Exception("columnNames and values do not match");
        }
        ArrayList<Entry> entries = new ArrayList<>();
        for (Column column : columns) {
            int index = StringHelper.indexOf(columnNames, column.getName());
            Comparable realValue = null;
            if (index == -1) {
                if (column.isNotNull()) {
                    throw new Exception(column.getName() + " can not be null");
                }
            } else if (values[index].equals("null")) {
                if (column.isNotNull()) {
                    throw new Exception(column.getName() + "columnNames can not be null");
                }
            } else {
                switch (column.getType()) {
                    case INT:
                        realValue = Integer.parseInt(values[index]);
                        break;
                    case LONG:
                        realValue = Long.parseLong(values[index]);
                        break;
                    case FLOAT:
                        realValue = Float.parseFloat(values[index]);
                        break;
                    case DOUBLE:
                        realValue = Double.parseDouble(values[index]);
                        break;
                    case STRING:
                        int n = values[index].length();
                        if (values[index].charAt(0) != '\'' || values[index].charAt(n - 1) != '\'') {
                            throw new Exception("TODO");
                        }
                        realValue = values[index].substring(1, n - 1);
                        break;
                    default:
                        throw new Exception("TODO");
                }
            }
            Entry entry = new Entry(realValue);
            entries.add(entry);
        }
        Row row = new Row(entries);
        insert(row);
        return row;
    }

    public Row insert(String[] values) throws Exception {
        if (values == null || values.length == 0) {
            throw new Exception("values is empty");
        }
        int nValues = values.length;
        if (nValues != columns.size()) {
            throw new Exception("columnNames and values do not match");
        }
        Entry[] entries = new Entry[nValues];
        for (int i = 0; i < nValues; i++) {
            Column column = columns.get(i);
            Comparable realValue = null;
            if (values[i].equals("null")) {
                if (column.isNotNull()) {
                    throw new Exception(column.getName() + "columnNames can not be null");
                }
            } else {
                switch (column.getType()) {
                    case INT:
                        realValue = Integer.parseInt(values[i]);
                        break;
                    case LONG:
                        realValue = Long.parseLong(values[i]);
                        break;
                    case FLOAT:
                        realValue = Float.parseFloat(values[i]);
                        break;
                    case DOUBLE:
                        realValue = Double.parseDouble(values[i]);
                        break;
                    case STRING:
                        int n = values[i].length();
                        if (values[i].charAt(0) != '\'' || values[i].charAt(n - 1) != '\'') {
                            throw new Exception("String format wrong");
                        }
                        realValue = values[i].substring(1, n - 1);
                        break;
                    default:
                        throw new Exception("TODO");
                }
            }
            entries[i] = new Entry(realValue);
        }
        Row row = new Row(entries);
        insert(row);
        return row;
    }

    public void delete(Row row) throws KeyNotExistException {
        Entry primary_key = row.getEntries().get(primaryIndex);
        boolean has_exist;
        try {
            lock.readLock().lock();
            has_exist = index.contains(primary_key);
        } finally {
            lock.readLock().unlock();
        }
        if (has_exist) {
            try {
                lock.writeLock().lock();
                Entry entry = row.getEntries().get(primaryIndex);
                index.remove(entry);
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            throw new KeyNotExistException(primary_key);
        }
    }

    public ArrayList<Row> clear() {
        ArrayList<Row> rows = new ArrayList<>();
        for (Pair<Entry, Row> entryRowPair : index) {
            index.remove(entryRowPair.getLeft());
            rows.add(entryRowPair.getRight());
        }
        return rows;
    }

    public Pair<Row, Row> update(Row oRow, String columnName, String newValue) throws Exception {
        int indexOfColumn = this.indexOfColumn(columnName);
        Comparable realValue = null;
        switch (columns.get(indexOfColumn).getType()) {
            case INT:
                realValue = Integer.parseInt(newValue);
                break;
            case LONG:
                realValue = Long.parseLong(newValue);
                break;
            case FLOAT:
                realValue = Float.parseFloat(newValue);
                break;
            case DOUBLE:
                realValue = Double.parseDouble(newValue);
                break;
            case STRING:
                int n = newValue.length();
                if (newValue.charAt(0) != '\'' || newValue.charAt(n - 1) != '\'') {
                    throw new Exception("String format wrong");
                }
                realValue = newValue.substring(1, n - 1);
                break;
            default:
                throw new Exception("TODO");
        }
        Entry updated_entry = new Entry(realValue);
        Row nRow = new Row(oRow.getEntries());
        ArrayList<Entry> entries = nRow.getEntries();
        entries.set(indexOfColumn, updated_entry);
        update(oRow, nRow);
        return new Pair<>(oRow, nRow);
    }

    public void update(Row oRow, Row nRow) throws Exception {
        Entry primary_key = oRow.getEntries().get(primaryIndex);
        boolean has_exist;
        try {
            lock.readLock().lock();
            has_exist = index.contains(primary_key);
        } finally {
            lock.readLock().unlock();
        }
        if (has_exist) {
            try {
                lock.writeLock().lock();
                // primary key update
                if (oRow.getEntries().get(primaryIndex) != nRow.getEntries().get(primaryIndex)) {
                    index.put(nRow.getEntries().get(primaryIndex), nRow);
                    index.remove(primary_key);
                } else {
                    index.update(primary_key, nRow);
                }
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            throw new Exception("Row has not existed!");
        }
    }

    public void addColumn(String name, String type, int maxLen) throws Exception {
        // 检查是否存在
        for (Column c : columns) {
            if (c.getName().equals(name)) {
                throw new Exception("column has existed.");
            }
        }
        // 增加属性
        ColumnType columnType = null;
        try {
            columnType = ColumnType.valueOf(type);
        } catch (Exception e) {
            throw new Exception("column type is invalid.");
        }
        columns.add(new Column(name, columnType, 0, false, maxLen));
        if (columns.size() == 1) {
            this.columns.get(0).setPrimary();
            this.primaryIndex = 0;
        }
        // 更新数据
        for (Row row : this) {
            row.getEntries().add(new Entry(null));
        }
    }

    public void dropColumn(String name) throws Exception {
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (c.getName().equals(name)) {
                // 主键无法删除
                if (c.isPrimary()) {
                    throw new Exception("Primary key can not be delete.");
                }
                columns.remove(i);
                // 更新数据
                for (Row row : this) {
                    row.getEntries().remove(i);
                }
                return;
            }
        }
        throw new Exception("column does not exist.");
    }

    public void alterColumn(String name, String type, int maxLen) throws Exception {
        ColumnType columnType = null;
        try {
            columnType = ColumnType.valueOf(type);
        } catch (Exception e) {
            throw new Exception("column type is invalid.");
        }
        for (Column c : columns) {
            if (c.getName().equals(name)) {
                c.setType(columnType);
                return;
            }
        }
        throw new Exception("column does not exist.");
    }

    public int indexOfColumn(String name) {
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (c.getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public String getPersistDir() {
        return Global.PERSIST_PATH + File.separator + databaseName + File.separator + tableName;
    }

    public String getRowsPersistFile() {
        return getPersistDir() + File.separator + tableName + Global.PERSIST_TABLE_ROWS_SUFFIX;
    }

    public boolean checkMakePersistDir() {
        File tableFolder = new File(getPersistDir());
        return (tableFolder.exists() && !tableFolder.isFile()) || tableFolder.mkdirs();
    }

    private boolean serialize() {
        try {
            if (!checkMakePersistDir()) {
                System.err.println("Failed while serializing table and dump it to disk: mkdir failed.");
                return false;
            }

            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(getRowsPersistFile()));
            for (Row row : this) { // this is iterable
                objectOutputStream.writeObject(row);
            }
            objectOutputStream.close();
            return true;
        } catch (IOException e) {
            System.err.println("Failed while serializing table: IO Exception.");
            return false;
        }
    }

    private ArrayList<Row> deserialize() {
        try {
            // Judge whether file "data/{databaseName}/tables/{tableName}.data" exists
            File tableFile = new File(getRowsPersistFile());
            if (!tableFile.exists() || tableFile.isDirectory()) {
                return new ArrayList<>();
            }

            ArrayList<Row> rows = new ArrayList<>();
            FileInputStream fileInputStream = new FileInputStream(tableFile);
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
            while (fileInputStream.available() > 0) {
                rows.add((Row) objectInputStream.readObject());
            }
            objectInputStream.close();
            fileInputStream.close();
            return rows;
        } catch (IOException e) {
            System.err.println("Failed while deserializing table: IO Exception.");
        } catch (ClassNotFoundException e) {
            System.err.println("Failed while deserializing table: ClassNotFoundException.");
        }
        return new ArrayList<>();
    }

    @Override
    public Iterator<Row> iterator() {
        return new TableIterator(this);
    }

    // Getter and Setter
    public String getTableName() {
        return tableName;
    }

    public void readLock() {
        lock.readLock().lock();
    }

    public void readUnlock() {
        lock.readLock().unlock();
    }

    public void writeLock() {
        lock.writeLock().lock();
    }

    public void writeUnlock() {
        lock.writeLock().unlock();
    }

    private class TableIterator implements Iterator<Row> {
        private Iterator<Pair<Entry, Row>> iterator;

        TableIterator(Table table) {
            this.iterator = table.index.iterator();
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Row next() {
            return iterator.next().right;
        }
    }
}

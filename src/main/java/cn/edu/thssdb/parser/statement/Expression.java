package cn.edu.thssdb.parser.statement;

public class Expression {
    public Comparer comparerLeft;
    public Comparer comparerRight = null;
    public OP op = null;

    public Expression(Comparer comparerLeft) {
        this.comparerLeft = comparerLeft;
    }

    public Expression(Comparer comparerLeft, OP op, Comparer comparerRight) {
        this.comparerLeft = comparerLeft;
        this.op = op;
        this.comparerRight = comparerRight;
    }

    public enum OP {
        MUL, DIV, ADD, SUB
    }
}

import app.kun.offline.KunModel;
import java.io.*;
import java.util.*;

public class KunModelTest {
    static double[] vector(DataInputStream in,int n)throws Exception {double[] a=new double[n];for(int i=0;i<n;i++)a[i]=in.readDouble();return a;}
    static double[][] matrix(DataInputStream in,int r,int c)throws Exception {double[][] a=new double[r][];for(int i=0;i<r;i++)a[i]=vector(in,c);return a;}
    static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    static void rejected(Runnable r){try{r.run();throw new AssertionError("Invalid model accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        KunModel m;
        try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]))) {
            int v=in.readInt();String[] vocab=new String[v];for(int i=0;i<v;i++)vocab[i]=new String(Character.toChars(in.readInt()));
            m=new KunModel(vocab,matrix(in,v,8),matrix(in,32,64),vector(in,32),matrix(in,v,32),vector(in,v));
            int[] ids=new int[8];for(int i=0;i<8;i++)ids[i]=in.readInt();
            double[] expected=vector(in,v),actual=m.probabilities(ids);double total=0;
            for(int i=0;i<v;i++){check(Math.abs(expected[i]-actual[i])<1e-10,"Python/Java inference mismatch");total+=actual[i];}
            check(Math.abs(total-1)<1e-12,"Invalid probability distribution");
        }
        check(m.generate("Hello",180,new Random(7)).codePointCount(0,m.generate("Hello",180,new Random(7)).length())==180,"Output length");
        check(m.generate("اردو 🙂",20,new Random(7)).length()>0,"Unicode/unknown input");
        Thread.currentThread().interrupt();
        try {m.generate("Hello",180,new Random());throw new AssertionError("Cancellation ignored");}
        catch(InterruptedException expected){}finally{Thread.interrupted();}
        String[] v={"🙂"};double[][] e=new double[1][8],w=new double[32][64],o=new double[1][32];double[] b=new double[32],d={0};
        KunModel unicode=new KunModel(v,e,w,b,o,d);
        check(unicode.generate("🙂",3,new Random()).equals("🙂🙂🙂"),"Unicode tokens corrupted");
        rejected(()->new KunModel(v,new double[2][8],w,b,o,d));
        e[0][0]=Double.NaN;rejected(()->new KunModel(v,e,w,b,o,d));
        e[0][0]=101;rejected(()->new KunModel(v,e,w,b,o,d));
        rejected(()->m.probabilities(new int[]{0}));
        System.out.println("PASS: Python/Java parity, normalized probabilities, bounded output, Unicode, cancellation and malformed-model rejection.");
    }
}

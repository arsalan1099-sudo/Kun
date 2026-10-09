package app.kun.offline;

import java.util.Random;

/** Small KUN character model. No JNI, MediaPipe, network, or third-party runtime. */
public final class KunModel {
    private final String[] vocabulary;
    private final double[][] embeddings, hiddenWeights, outputWeights;
    private final double[] hiddenBias, outputBias;
    private static final int CONTEXT=8, EMBEDDING=8, HIDDEN=32;
    public KunModel(String[] vocabulary, double[][] embeddings, double[][] hiddenWeights,
                    double[] hiddenBias, double[][] outputWeights, double[] outputBias) {
        if(vocabulary==null || vocabulary.length<1 || vocabulary.length>512)
            throw new IllegalArgumentException("Vocabulary must have 1–512 characters.");
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(String s:vocabulary) {
            if(s==null || s.codePointCount(0,s.length())!=1 || !seen.add(s))
                throw new IllegalArgumentException("Invalid or duplicate character.");
        }
        this.vocabulary=vocabulary.clone();
        this.embeddings=matrix(embeddings,vocabulary.length,EMBEDDING);
        this.hiddenWeights=matrix(hiddenWeights,HIDDEN,CONTEXT*EMBEDDING);
        this.hiddenBias=vector(hiddenBias,HIDDEN);
        this.outputWeights=matrix(outputWeights,vocabulary.length,HIDDEN);
        this.outputBias=vector(outputBias,vocabulary.length);
    }
    private static double[] vector(double[] a,int length) {
        if(a==null || a.length!=length)throw new IllegalArgumentException("Wrong model dimensions.");
        for(double n:a)if(!Double.isFinite(n) || Math.abs(n)>100)
            throw new IllegalArgumentException("Invalid model weights.");
        return a.clone();
    }
    private static double[][] matrix(double[][] a,int rows,int cols) {
        if(a==null || a.length!=rows)throw new IllegalArgumentException("Wrong model dimensions.");
        double[][] result=new double[rows][];
        for(int i=0;i<rows;i++)result[i]=vector(a[i],cols);
        return result;
    }
    public double[] probabilities(int[] ids) {
        if(ids.length!=CONTEXT)throw new IllegalArgumentException("Expected eight input characters.");
        double[] x=new double[CONTEXT*EMBEDDING],h=new double[HIDDEN],p=new double[vocabulary.length];
        for(int i=0;i<CONTEXT;i++) {
            if(ids[i]<0 || ids[i]>=vocabulary.length)throw new IllegalArgumentException("Unknown character ID.");
            System.arraycopy(embeddings[ids[i]],0,x,i*EMBEDDING,EMBEDDING);
        }
        for(int j=0;j<HIDDEN;j++) {
            double sum=hiddenBias[j];
            for(int k=0;k<x.length;k++)sum+=hiddenWeights[j][k]*x[k];
            h[j]=Math.tanh(sum);
        }
        double peak=-Double.MAX_VALUE;
        for(int i=0;i<p.length;i++) {
            double sum=outputBias[i];
            for(int j=0;j<HIDDEN;j++)sum+=outputWeights[i][j]*h[j];
            p[i]=sum;peak=Math.max(peak,sum);
        }
        double total=0;
        for(int i=0;i<p.length;i++){p[i]=Math.exp(p[i]-peak);total+=p[i];}
        for(int i=0;i<p.length;i++)p[i]/=total;
        return p;
    }
    public String generateResponse(String prompt) throws InterruptedException {
        return generate(prompt,180,new Random());
    }
    public String generate(String prompt,int count,Random random) throws InterruptedException {
        if(prompt==null || prompt.length()>5000 || count<1 || count>512)
            throw new IllegalArgumentException("Input or output too long.");
        java.util.HashMap<String,Integer> map=new java.util.HashMap<>();
        for(int i=0;i<vocabulary.length;i++)map.put(vocabulary[i],i);
        int fallback=map.containsKey(" ")?map.get(" "):0;
        int[] ids=new int[CONTEXT];java.util.Arrays.fill(ids,fallback);
        int[] points=prompt.codePoints().toArray();
        for(int k=Math.max(0,points.length-CONTEXT);k<points.length;k++) {
            String s=new String(Character.toChars(points[k]));
            System.arraycopy(ids,1,ids,0,CONTEXT-1);
            ids[CONTEXT-1]=map.containsKey(s)?map.get(s):fallback;
        }
        StringBuilder out=new StringBuilder();
        for(int n=0;n<count;n++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Generation cancelled.");
            double[] p=probabilities(ids);double total=0;
            for(int i=0;i<p.length;i++){p[i]=Math.pow(p[i],1/.8);total+=p[i];}
            double choice=random.nextDouble()*total;int selected=p.length-1;
            for(int i=0;i<p.length;i++){choice-=p[i];if(choice<0){selected=i;break;}}
            out.append(vocabulary[selected]);System.arraycopy(ids,1,ids,0,CONTEXT-1);ids[CONTEXT-1]=selected;
        }
        return out.toString();
    }
}

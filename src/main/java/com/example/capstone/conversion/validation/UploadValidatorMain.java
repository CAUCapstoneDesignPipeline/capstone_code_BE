package com.example.capstone.conversion.validation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Heap·wall time은 부모 프로세스가 제한한다. OS sandbox나 전체 RSS 제한을 주장하지 않는다. */
public final class UploadValidatorMain {
    private static final long PIXELS=25_000_000L;
    private static final Set<String> FORBIDDEN=Set.of("JS","JavaScript","AA","OpenAction","EmbeddedFiles","EF","AF","AcroForm","URI","Launch","GoToR","GoToE","SubmitForm","ImportData","RichMedia","XFA");
    private static final class Limit extends Exception { }
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IOException();Path file=Path.of(args[0]);long size=Files.size(file);
            if(size<1 || size>10_485_760)throw new Limit();byte[] prefix;
            try(var in=Files.newInputStream(file)) { prefix=in.readNBytes(8); }
            String type=prefix.length>=8 && Arrays.equals(prefix,new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png":
                prefix.length>=3 && prefix[0]==(byte)255 && prefix[1]==(byte)216 && prefix[2]==(byte)255?"image/jpeg":
                prefix.length>=5 && new String(prefix,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")?"application/pdf":"";
            if(!type.equals(args[1]))throw new IOException();
            int pages=type.equals("application/pdf")?pdf(file):image(file,type);
            System.out.print("OK "+type+" "+pages);
        } catch(Limit limit) { System.exit(3); }
        catch(Throwable invalid) { System.exit(2); }
    }
    private static void pixels(long width,long height) throws Limit {
        if(width<1 || height<1 || width>PIXELS || height>PIXELS || width>PIXELS/height)throw new Limit();
    }
    private static int image(Path path,String type) throws Exception {
        if(type.equals("image/png"))pngChunks(path);
        else { try(var in=new RandomAccessFile(path.toFile(),"r")) { if(in.length()<4)throw new IOException();in.seek(in.length()-2);if(in.readUnsignedShort()!=0xffd9)throw new IOException(); } }
        ImageIO.setUseCache(false);
        try(var stream=ImageIO.createImageInputStream(path.toFile())) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException();var reader=readers.next();
            try {
                final boolean[] warning={false};reader.addIIOReadWarningListener((source,text)->warning[0]=true);
                reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);pixels(w,h);
                var decoded=reader.read(0);if(decoded==null || decoded.getWidth()!=w || decoded.getHeight()!=h || warning[0])throw new IOException();decoded.flush();
            } finally { reader.dispose(); }
        }
        return 1;
    }
    private static void pngChunks(Path file) throws Exception {
        try(var in=new DataInputStream(Files.newInputStream(file))) {
            in.skipNBytes(8);boolean first=true,end=false;long bytes=8;
            while(!end) {
                int n=in.readInt();if(n<0 || n>10_485_760 || bytes+12L+n>Files.size(file))throw new IOException();
                byte[] type=in.readNBytes(4),data=in.readNBytes(n);if(type.length!=4 || data.length!=n)throw new EOFException();
                String name=new String(type,java.nio.charset.StandardCharsets.US_ASCII);if(first && (!name.equals("IHDR") || n!=13))throw new IOException();first=false;
                var crc=new CRC32();crc.update(type);crc.update(data);if((int)crc.getValue()!=in.readInt())throw new IOException();bytes+=n+12L;
                if(name.equals("IEND")) { if(n!=0)throw new IOException();end=true; }
            }
            if(in.read()!=-1)throw new IOException();
        }
    }
    private static int pdf(Path path) throws Exception {
        try(var input=new RandomAccessReadBufferedFile(path.toFile());var document=new PDFParser(input).parse(false)) {
            if(document.isEncrypted())throw new IOException();int count=document.getNumberOfPages();if(count<1 || count>20)throw new Limit();
            Set<COSBase> visited=Collections.newSetFromMap(new IdentityHashMap<>());long[] expanded={0};
            for(var key:document.getDocument().getXrefTable().keySet())walk(document.getDocument().getObjectFromPool(key).getObject(),visited,expanded,0);
            var renderer=new PDFRenderer(document);
            for(int index=0;index<count;index++) {
                var page=document.getPage(index);COSBase rawUnit=inherited(page.getCOSObject(),"UserUnit");double unit=rawUnit==null?1:number(rawUnit);
                if(!Double.isFinite(unit) || unit<=0 || unit>75000)throw new IOException();
                COSBase media=inherited(page.getCOSObject(),"MediaBox"),crop=inherited(page.getCOSObject(),"CropBox");box(media,unit);box(crop==null?media:crop,unit);
                COSBase rotation=inherited(page.getCOSObject(),"Rotate");if(rotation!=null && (number(rotation)%90!=0))throw new IOException();
                scanInline(new PDFStreamParser(page));
                // 실제 stream/font/image 디코드. 페이지 하나씩 해제하여 합계 raster 누적을 피한다.
                var raster=renderer.renderImageWithDPI(index,(float)(300*unit));pixels(raster.getWidth(),raster.getHeight());raster.flush();
            }
            return count;
        }
    }
    private static void scanInline(PDFStreamParser parser) throws Exception {
        try { int count=0;Object token;while((token=parser.parseNextToken())!=null) {
            if(++count>100000)throw new Limit();
            if(token instanceof Operator op && op.getName().equals("BI")) {
                var parameters=op.getImageParameters();
                COSBase width=parameters.getDictionaryObject(COSName.WIDTH,COSName.getPDFName("W"));
                COSBase height=parameters.getDictionaryObject(COSName.HEIGHT,COSName.getPDFName("H"));
                double w=number(width),h=number(height);if(w!=Math.rint(w) || h!=Math.rint(h))throw new IOException();pixels((long)w,(long)h);
            }
        } } finally { parser.close(); }
    }
    private static COSBase inherited(COSDictionary page,String name) throws IOException {
        Set<COSDictionary> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(page!=null) { if(!seen.add(page))throw new IOException();COSBase found=page.getDictionaryObject(COSName.getPDFName(name));if(found!=null)return found;COSBase parent=page.getDictionaryObject(COSName.PARENT);page=parent instanceof COSDictionary d?d:null; }
        return null;
    }
    private static double number(COSBase value) throws IOException { if(!(value instanceof COSNumber n))throw new IOException();double d=n.floatValue();if(!Double.isFinite(d))throw new IOException();return d; }
    private static void box(COSBase value,double unit) throws Exception {
        if(!(value instanceof COSArray a) || a.size()!=4)throw new IOException();double x=number(a.getObject(2))-number(a.getObject(0)),y=number(a.getObject(3))-number(a.getObject(1));
        if(!Double.isFinite(x) || !Double.isFinite(y) || x<=0 || y<=0)throw new IOException();double w=Math.ceil(x*unit*300/72),h=Math.ceil(y*unit*300/72);
        if(!Double.isFinite(w) || !Double.isFinite(h) || w>PIXELS || h>PIXELS)throw new Limit();pixels((long)w,(long)h);
    }
    private static void walk(COSBase value,Set<COSBase> seen,long[] expanded,int depth) throws Exception {
        if(value==null || !seen.add(value))return;if(depth>100 || seen.size()>100000)throw new Limit();
        if(value instanceof COSObject object)walk(object.getObject(),seen,expanded,depth+1);
        else if(value instanceof COSArray array)for(var item:array)walk(item,seen,expanded,depth+1);
        else if(value instanceof COSDictionary dictionary) {
            for(var entry:dictionary.entrySet()) {
                if(FORBIDDEN.contains(entry.getKey().getName()))throw new IOException();
                if(entry.getValue() instanceof COSName name && (FORBIDDEN.contains(name.getName()) || Set.of("Filespec","EmbeddedFile").contains(name.getName())))throw new IOException();
                walk(entry.getValue(),seen,expanded,depth+1);
            }
            if(dictionary instanceof COSStream stream) {
                if(stream.containsKey(COSName.F) || stream.containsKey(COSName.F_FILTER))throw new IOException();
                if("Image".equals(stream.getNameAsString(COSName.SUBTYPE)))pixels(stream.getLong(COSName.WIDTH),stream.getLong(COSName.HEIGHT));
                if("Form".equals(stream.getNameAsString(COSName.SUBTYPE)) || stream.getInt(COSName.getPDFName("PatternType"))==1) {
                    try(var decoded=stream.createInputStream()) { byte[] content=decoded.readNBytes(16*1024*1024+1);if(content.length>16*1024*1024)throw new Limit();scanInline(new PDFStreamParser(content)); }
                }
                try(var decoded=stream.createInputStream()) { byte[] buffer=new byte[8192];long own=0;int n;while((n=decoded.read(buffer))!=-1) { own+=n;expanded[0]+=n;if(own>64L*1024*1024 || expanded[0]>128L*1024*1024)throw new Limit(); } }
            }
        }
    }
}

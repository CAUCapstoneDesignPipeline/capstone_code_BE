package com.example.capstone.conversion;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.cos.*;
import com.example.capstone.conversion.config.ConversionProperties;
import com.example.capstone.conversion.service.*;
import com.example.capstone.global.exception.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadValidationTest {
    @TempDir Path root;
    PrivateSourceStorage storage() { return new PrivateSourceStorage(new ConversionProperties(false,root.resolve("private").toString(),System.getProperty("capstone.test.validator-jar"),5,120,false,0,null)); }
    static byte[] png() throws Exception { var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray(); }
    static byte[] pdf(java.util.function.Consumer<PDDocument> setup) throws Exception { try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) { pdf.addPage(new PDPage());setup.accept(pdf);pdf.save(bytes);return bytes.toByteArray(); } }
    @Test void validatesActualPngJpegPdfWithPrivatePermissions() throws Exception {
        var store=storage();var p=store.put(new ByteArrayInputStream(png()),"image/png");assertEquals(1,p.pages());assertArrayEquals(png(),store.read(p.key(),p.hash(),p.bytes()));assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path(p.key()))));
        var jpeg=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);assertEquals("image/jpeg",store.put(new ByteArrayInputStream(jpeg.toByteArray()),"image/jpeg").type());
        assertEquals(1,store.put(new ByteArrayInputStream(pdf(d->{})),"application/pdf").pages());
    }
    private void rejected(byte[] bytes,String media,ErrorCode expected) { assertEquals(expected,assertThrows(ApiException.class,()->storage().put(new ByteArrayInputStream(bytes),media)).errorCode()); }
    @Test void rejectsDeclaredMismatchDamagedBytesLimitsAndActivePdf() throws Exception {
        rejected(png(),"application/pdf",ErrorCode.UNSUPPORTED_FILE_TYPE);byte[] damaged=png();damaged[damaged.length-1]^=1;rejected(damaged,"image/png",ErrorCode.UNSUPPORTED_FILE_TYPE);
        rejected(new byte[10_485_761],"image/png",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
        rejected(pdf(d->{for(int i=0;i<20;i++)d.addPage(new PDPage());}),"application/pdf",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
        rejected(pdf(d->d.getPage(0).setMediaBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(2000,2000))),"application/pdf",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
        rejected(pdf(d->d.getPage(0).getCOSObject().setFloat(COSName.getPDFName("UserUnit"),100)),"application/pdf",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
        rejected(pdf(d->d.getDocumentCatalog().getCOSObject().setString(COSName.getPDFName("JavaScript"),"forbidden")),"application/pdf",ErrorCode.UNSUPPORTED_FILE_TYPE);
        rejected(pdf(d->{COSArray box=new COSArray();box.add(COSInteger.get(100));box.add(COSInteger.ZERO);box.add(COSInteger.ZERO);box.add(COSInteger.get(100));d.getPage(0).getCOSObject().setItem(COSName.CROP_BOX,box);}),"application/pdf",ErrorCode.UNSUPPORTED_FILE_TYPE);
        try(var files=Files.list(root.resolve("private"))) { assertEquals(0,files.count()); }
    }
    @Test void childTimeoutAndHeapFailureLeaveNoFilesOrProcesses() throws Exception {
        for(String body:java.util.List.of("Thread.sleep(60000);","var allocations=new java.util.ArrayList<byte[]>();while(true)allocations.add(new byte[1024*1024]);")) {
            Path dir=Files.createTempDirectory(root,"child-");Path source=dir.resolve("Probe.java");Files.writeString(source,"public class Probe { public static void main(String[] args)throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]+\".pid\"),Long.toString(ProcessHandle.current().pid()));"+body+"} }");
            int compiled=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),source.toString());assertEquals(0,compiled);
            Path jar=dir.resolve("probe.jar");var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","Probe");
            try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest)) {out.putNextEntry(new java.util.jar.JarEntry("Probe.class"));out.write(Files.readAllBytes(dir.resolve("Probe.class")));out.closeEntry();}
            Path storage=dir.resolve("private");var store=new PrivateSourceStorage(new ConversionProperties(false,storage.toString(),jar.toString(),1,120,false,0,null));long start=System.nanoTime();
            assertEquals(body.contains("allocations")?ErrorCode.UPLOAD_LIMIT_EXCEEDED:ErrorCode.UNSUPPORTED_FILE_TYPE,assertThrows(ApiException.class,()->store.put(new ByteArrayInputStream(png()),"image/png")).errorCode());assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
            try(var files=Files.list(storage)) { for(Path path:files.toList()) { assertTrue(path.toString().endsWith(".pid"));long pid=Long.parseLong(Files.readString(path));assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));Files.delete(path); } }
        }
    }
    @Test void exactBytePageAndPixelLimitsAndInheritedGeometry() throws Exception {
        byte[] small=png();int padding=10_485_760-small.length-12;var padded=new ByteArrayOutputStream();padded.write(small,0,small.length-12);
        var output=new DataOutputStream(padded);output.writeInt(padding);byte[] kind="npAD".getBytes(java.nio.charset.StandardCharsets.US_ASCII),data=new byte[padding];output.write(kind);output.write(data);var crc=new java.util.zip.CRC32();crc.update(kind);crc.update(data);output.writeInt((int)crc.getValue());padded.write(small,small.length-12,12);
        var store=storage();assertEquals(10_485_760,store.put(new ByteArrayInputStream(padded.toByteArray()),"image/png").bytes());
        assertEquals(1,store.put(new ByteArrayInputStream(pdf(d->d.getPage(0).setMediaBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(1200,1200)))),"application/pdf").pages());
        assertEquals(20,store.put(new ByteArrayInputStream(pdf(d->{d.getPage(0).setMediaBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10,10));for(int i=1;i<20;i++)d.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(10,10)));})),"application/pdf").pages());
        byte[] giant=small.clone();java.nio.ByteBuffer.wrap(giant,16,8).putInt(5001).putInt(5000);crc.reset();crc.update(giant,12,17);java.nio.ByteBuffer.wrap(giant,29,4).putInt((int)crc.getValue());rejected(giant,"image/png",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
        rejected(pdf(d->d.getPage(0).getCOSObject().removeItem(COSName.MEDIA_BOX)),"application/pdf",ErrorCode.UNSUPPORTED_FILE_TYPE);
        rejected(pdf(d->{try{d.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","user",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));}catch(Exception e){throw new IllegalStateException(e);}}),"application/pdf",ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    @Test void rejectsOversizedNestedFormInlineImageBeforeRendering() throws Exception {
        byte[] bytes=pdf(d->{try{
            var form=new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(d);form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10,10));
            try(var out=form.getCOSObject().createOutputStream()){out.write("BI /W 5001 /H 5000 /CS /RGB /BPC 8 ID x EI".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            d.getPage(0).setResources(new PDResources());d.getPage(0).getResources().put(COSName.getPDFName("Nested"),form);
        }catch(Exception e){throw new IllegalStateException(e);}});
        rejected(bytes,"application/pdf",ErrorCode.UPLOAD_LIMIT_EXCEEDED);
    }

}

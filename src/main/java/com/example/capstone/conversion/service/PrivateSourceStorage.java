package com.example.capstone.conversion.service;

import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import com.example.capstone.conversion.config.ConversionProperties;
import static com.example.capstone.conversion.service.ConversionErrors.*;

@Component
public class PrivateSourceStorage implements SourceStorage {
    public static final int MAX_BYTES=10_485_760;
    private final ConversionProperties config;
    private final Semaphore validators=new Semaphore(1);
    public PrivateSourceStorage(ConversionProperties config) { this.config=config; }
    public boolean configured() {
        try {
            if(!config.bounded() || config.storageRoot()==null || !Path.of(config.storageRoot()).isAbsolute() || config.validatorJar()==null || !Files.isRegularFile(Path.of(config.validatorJar()),LinkOption.NOFOLLOW_LINKS))return false;
            Path root=Path.of(config.storageRoot());
            if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))return Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) && Files.isWritable(root);
            Path parent=root.getParent();while(parent!=null && !Files.exists(parent))parent=parent.getParent();return parent!=null && Files.isDirectory(parent) && Files.isWritable(parent);
        }
        catch(RuntimeException invalid) { return false; }
    }
    private Path root() throws Exception {
        if(!configured())throw unavailable();Path root=Path.of(config.storageRoot()).normalize();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))throw unavailable();
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));return root.toRealPath();
    }
    public Path path(UUID key) { try { Path p=root().resolve(key.toString());if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw unavailable();return p; } catch(Exception error) { throw unavailable(); } }
    public Stored put(InputStream input,String declared) {
        UUID key=UUID.randomUUID();Path temporary=null,target=null;
        try {
            if(!Set.of("image/png","image/jpeg","application/pdf").contains(declared==null?"":declared))throw unsupported();
            Path root=root();temporary=root.resolve(key+".part");target=root.resolve(key.toString());
            Files.createFile(temporary,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            var digest=MessageDigest.getInstance("SHA-256");long size=0;
            try(input;var out=Files.newOutputStream(temporary,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1) { size+=n;if(size>MAX_BYTES)throw limit();digest.update(buffer,0,n);out.write(buffer,0,n); }
            }
            if(size==0)throw unsupported();int pages=validate(temporary,declared);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            return new Stored(key,declared,HexFormat.of().formatHex(digest.digest()),size,pages);
        } catch(com.example.capstone.global.exception.ApiException error) { removeQuietly(temporary);removeQuietly(target);throw error; }
        catch(Exception error) { removeQuietly(temporary);removeQuietly(target);throw unavailable(); }
    }
    int validate(Path file,String type) throws Exception {
        if(!validators.tryAcquire())throw unavailable();Process child=null;
        try {
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms16m","-Xmx256m","-XX:+ExitOnOutOfMemoryError","-Djava.awt.headless=true","-jar",Path.of(config.validatorJar()).toAbsolutePath().toString(),file.toString(),type);
            for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"))builder.environment().remove(key);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);child=builder.start();
            if(!child.waitFor(config.validationSeconds(),TimeUnit.SECONDS))throw unsupported();
            byte[] output=child.getInputStream().readNBytes(128);
            if(child.exitValue()==3)throw limit();if(child.exitValue()!=0 || output.length>=128)throw unsupported();
            String result=new String(output,java.nio.charset.StandardCharsets.US_ASCII);String prefix="OK "+type+" ";
            if(!result.startsWith(prefix))throw unsupported();int pages=Integer.parseInt(result.substring(prefix.length()));if(pages<1 || pages>20)throw unsupported();return pages;
        } finally {
            if(child!=null) { if(child.isAlive())child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);child.getInputStream().close();child.getErrorStream().close();child.getOutputStream().close(); }
            validators.release();
        }
    }
    public byte[] read(UUID key,String hash,long size) {
        try(var in=Files.newInputStream(path(key),LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=in.readNBytes(MAX_BYTES+1);if(bytes.length!=size || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash))throw unavailable();return bytes;
        } catch(Exception invalid) { throw unavailable(); }
    }
    public boolean delete(UUID key) { try { Files.deleteIfExists(root().resolve(key.toString()));return true; } catch(Exception failure) { return false; } }
    public void discard(Stored file) { delete(file.key()); }
    public List<Path> abandonedCandidates() {
        try(var paths=Files.list(root())) { long before=System.currentTimeMillis()-3_600_000;return paths.filter(p->{try{return Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toMillis()<before;}catch(Exception e){return false;}}).limit(100).toList(); }
        catch(Exception failure) { return List.of(); }
    }
    public void deleteAbandoned(Path path) { try { if(path.getParent().equals(root()))Files.deleteIfExists(path); } catch(Exception ignored) { } }
    @Override public void garbageCollect(java.util.function.Predicate<UUID> referenced) {
        for(Path path:abandonedCandidates()) { String name=path.getFileName().toString();
            try { UUID key=UUID.fromString(name.endsWith(".part")?name.substring(0,name.length()-5):name);if(!referenced.test(key))deleteAbandoned(path); }
            catch(IllegalArgumentException ignored) { }
        }
    }
    private void removeQuietly(Path file) { if(file!=null)try { Files.deleteIfExists(file); } catch(Exception ignored) { /* 1시간 staging/orphan 재시도가 회수한다. */ } }
}

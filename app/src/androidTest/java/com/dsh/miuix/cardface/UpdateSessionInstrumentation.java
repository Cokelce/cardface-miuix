package com.dsh.miuix.cardface;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Device regression: verifies real app-UID APK access and session staging, without committing/installing or launching UI. */
public class UpdateSessionInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
 @Override public void onStart() {
  Bundle out = new Bundle();
  int sessionId = -1;
  try {
   Context ctx = getTargetContext();
   File apk = new File(ctx.getApplicationInfo().sourceDir);
   Class<?> verifier = Class.forName("com.dsh.miuix.cardface.core.VerifiedUpdateDownload");
   Object instance = verifier.getField("INSTANCE").get(null);
   boolean valid = (Boolean)verifier.getMethod("verifyArchive", Context.class, File.class, boolean.class).invoke(instance,ctx,apk,false);
   if(!valid) throw new AssertionError("installed APK archive/signature validation rejected");
   out.putString("archive", "PASS same package/signature/version accepted");
   boolean newer=(Boolean)verifier.getMethod("verifyArchive",Context.class,File.class,boolean.class).invoke(instance,ctx,apk,true);
   if(newer) throw new AssertionError("same version accepted as newer download");
   out.putString("version_guard", "PASS equal version not offered as new update");
   PackageInstaller installer=ctx.getPackageManager().getPackageInstaller();
   PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
   params.setAppPackageName(ctx.getPackageName()); params.setSize(apk.length());
   sessionId=installer.createSession(params);
   PackageInstaller.Session session=installer.openSession(sessionId);
   try {
    try(OutputStream dest=session.openWrite("base.apk",0,apk.length()); InputStream input=new FileInputStream(apk)) {
     byte[] buffer=new byte[65536]; int n; while((n=input.read(buffer))>=0) dest.write(buffer,0,n); session.fsync(dest);
    }
    byte[] original; try(InputStream input=new FileInputStream(apk)) { original=digest(input); }
    byte[] staged; try(InputStream input=session.openRead("base.apk")) { staged=digest(input); }
    if(!Arrays.equals(original,staged)) throw new AssertionError("session staged bytes differ");
    out.putString("session", "PASS create/openWrite/fsync/openRead SHA256 equality");
   } finally { session.close(); }
   installer.abandonSession(sessionId); sessionId=-1;
   out.putString("result", "3 device checks passed; no session committed; no Activity launched");
   finish(-1,out);
  } catch(Throwable t) {
   out.putString("failure",String.valueOf(t)); finish(0,out);
  } finally {
   if(sessionId>=0) try { getTargetContext().getPackageManager().getPackageInstaller().abandonSession(sessionId); } catch(Throwable ignored) {}
  }
 }
 private byte[] digest(InputStream in) throws Exception {
  MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
  while((n=in.read(b))>=0) d.update(b,0,n);return d.digest();
 }
}

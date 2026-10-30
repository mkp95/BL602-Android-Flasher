package io.mkp95.blflashdroid;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.*; import java.security.MessageDigest; import java.util.Arrays;
final class Bl602Flasher {
 interface Listener { void log(String s); void progress(int p); }
 static final int BAUD=200000, APP_OFFSET=0x10000, FW_END=0x150000;
 private final UsbSerialPort port; private final Listener listener;
 Bl602Flasher(UsbSerialPort p,Listener l){port=p;listener=l;}
 void flash(byte[] fw,byte[] loader,byte[] para)throws Exception{
  if(fw==null||fw.length==0)throw new IOException("Firmware file is empty.");
  if(fw.length>FW_END-APP_OFFSET)throw new IOException("Firmware exceeds configured FW partition 0x10000–0x14FFFF. Refusing to erase.");
  Image im=parse(loader); log("BL602 UART programmer; 200000 baud maximum (8-N-1)");
  log("FW target 0x00010000, "+fw.length+" bytes; loader RAM 0x"+hx(im.addr));
  port.setParameters(BAUD,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);drain();
  handshake("BootROM"); bootCmd(0x10,new byte[0],22);log("BootROM info received");
  bootCmd(0x11,im.header,0);bootCmd(0x17,im.segHeader,16);
  int sent=0;while(sent<im.data.length){int n=Math.min(4092,im.data.length-sent);bootCmd(0x18,Arrays.copyOfRange(im.data,sent,sent+n),0);sent+=n;progress(5+sent*15/im.data.length);}
  bootCmd(0x19,new byte[0],0);bootCmd(0x1a,new byte[0],0);log("RAM eflash loader started");Thread.sleep(100);drain();handshake("RAM loader");
  byte[] pp=new byte[4+para.length];put32(pp,0,255);System.arraycopy(para,0,pp,4,para.length);loaderCmd(0x3b,pp,0,10000);
  byte[] jid=loaderCmd(0x36,new byte[0],4,5000);String id=hex(jid);log("Flash JEDEC ID: "+id);
  if(!"ef4015".equalsIgnoreCase(id))throw new IOException("Flash ID "+id+" does not match bundled BL602 flash parameters EF4015. No erase was sent.");
  int end=APP_OFFSET+fw.length-1;log("Erase range 0x"+hx(APP_OFFSET)+"–0x"+hx(end));
  byte[] erase=new byte[8];put32(erase,0,APP_OFFSET);put32(erase,4,end);loaderCmd(0x30,erase,0,30000);log("Erase complete");
  byte[] host=MessageDigest.getInstance("SHA-256").digest(fw);int off=0;
  while(off<fw.length){int n=Math.min(2048,fw.length-off);byte[] data=new byte[n+4];put32(data,0,APP_OFFSET+off);System.arraycopy(fw,off,data,4,n);loaderCmd(0x31,data,0,12000);off+=n;progress(20+off*65/fw.length);if(off==fw.length||off%32768==0)log("Written "+off+"/"+fw.length+" bytes");}
  loaderCmd(0x3a,new byte[0],0,15000);log("Loader write-check passed");
  byte[] req=new byte[8];put32(req,0,APP_OFFSET);put32(req,4,fw.length);byte[] device=loaderCmd(0x3e,req,32,30000);
  if(!MessageDigest.isEqual(host,device))throw new IOException("SHA-256 verification failed. Host "+hex(host)+" device "+hex(device));
  progress(100);log("SHA-256 verified: "+hex(host));loaderCmd(0x21,new byte[0],0,5000);log("Chip reset; booting from flash");log("FLASH SUCCESS");
 }
 private static final class Image{byte[]header,segHeader,data;int addr;}
 private Image parse(byte[]b)throws IOException{
  if(b==null||b.length<192)throw new IOException("Bundled BL602 loader missing/truncated.");
  if(u32(b,0)!=0x504e4642)throw new IOException("Bundled loader BFNP header invalid.");
  int flags=u32(b,116);if((flags&0x0f)!=0)throw new IOException("Encrypted/signed loader unsupported.");
  if(u32(b,120)!=1)throw new IOException("Expected one segment in bundled eflash loader.");
  int p=176,addr=u32(b,p),len=u32(b,p+4);if(len<=0||len>b.length-p-16||p+16+len!=b.length)throw new IOException("Loader segment length invalid.");
  if(addr!=0x22010000)throw new IOException("Unexpected loader RAM address.");
  Image x=new Image();x.header=Arrays.copyOfRange(b,0,176);x.segHeader=Arrays.copyOfRange(b,p,p+16);x.data=Arrays.copyOfRange(b,p+16,b.length);x.addr=addr;return x;
 }
 private void handshake(String phase)throws Exception{byte[]s=new byte[100];Arrays.fill(s,(byte)0x55);port.write(s,1000);byte[]a=read(2,2500);if(a[0]!='O'||a[1]!='K')throw err(a,phase+" handshake");Thread.sleep(20);log(phase+" handshake OK @ 200000");}
 private void bootCmd(int id,byte[]d,int trailingBytes)throws Exception{byte[]p=new byte[4+d.length];p[0]=(byte)id;p[2]=(byte)d.length;p[3]=(byte)(d.length>>>8);System.arraycopy(d,0,p,4,d.length);port.write(p,5000);byte[]a=read(2,5000);if(a[0]!='O'||a[1]!='K')throw err(a,"BootROM cmd 0x"+hx(id));if(trailingBytes>0)read(trailingBytes,5000);}
 private byte[] loaderCmd(int id,byte[]d,int response,int timeout)throws Exception{
  int len=d.length,sum=(len&255)+(len>>>8&255);for(byte b:d)sum+=b&255;
  byte[]p=new byte[4+len];p[0]=(byte)id;p[1]=(byte)sum;p[2]=(byte)len;p[3]=(byte)(len>>>8);System.arraycopy(d,0,p,4,len);
  port.write(p,Math.max(3000,p.length*20));long deadline=System.currentTimeMillis()+timeout;
  while(true){int left=(int)Math.max(1,deadline-System.currentTimeMillis());byte[]a=read(2,left);if(a[0]=='P'&&a[1]=='D')continue;if(a[0]!='O'||a[1]!='K')throw err(a,"Loader cmd 0x"+hx(id));if(response==0)return new byte[0];byte[]h=read(2,left);int n=(h[0]&255)|((h[1]&255)<<8);if(n<response)throw new IOException("Short loader response: "+n);return Arrays.copyOf(read(n,left),response);}
 }
 private byte[]read(int size,int timeout)throws Exception{byte[]out=new byte[size];int pos=0;long end=System.currentTimeMillis()+timeout;while(pos<size&&System.currentTimeMillis()<end){byte[]tmp=new byte[size-pos];int n=port.read(tmp,(int)Math.max(1,end-System.currentTimeMillis()));if(n>0){System.arraycopy(tmp,0,out,pos,n);pos+=n;}}if(pos!=size)throw new IOException("UART timeout: expected "+size+" bytes, received "+pos);return out;}
 private void drain()throws Exception{byte[]b=new byte[256];for(int i=0;i<4;i++){if(port.read(b,100)<=0)break;}}
 private IOException err(byte[]b,String where){if(b.length>=4&&b[0]=='F'&&b[1]=='L')return new IOException(where+" device error 0x"+hx((b[2]&255)|((b[3]&255)<<8)));return new IOException(where+" failed; response "+hex(b));}
 private void log(String s){listener.log(s+"\n");}private void progress(int p){listener.progress(p);}
 private static int u32(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
 private static void put32(byte[]b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>>8);b[p+2]=(byte)(v>>>16);b[p+3]=(byte)(v>>>24);}
 private static String hx(int n){return String.format(java.util.Locale.US,"%08X",n);}private static String hex(byte[]b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.US,"%02x",x&255));return s.toString();}
}
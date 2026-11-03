package com.xiaopeng.qa.camera;
import java.io.*;
import java.util.Arrays;
public class ICLinkProtocolTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static byte[] avccNal(int length,int header) {
        byte[] b=new byte[4+length]; Arrays.fill(b,4,b.length,(byte)0x55);
        b[0]=(byte)(length>>>24);b[1]=(byte)(length>>>16);b[2]=(byte)(length>>>8);b[3]=(byte)length;
        b[4]=(byte)header;return b;
    }
    static byte[] canonicalNal(int length,int header) {
        byte[] b=avccNal(length,header);b[0]=b[1]=b[2]=0;b[3]=1;return b;
    }
    static void rejectFraming(byte[] b)throws Exception {
        boolean rejected=false;try{ICLinkProtocol.annexB(b);}catch(IOException e){rejected=true;}
        check(rejected);
    }
    static void framingTests()throws Exception {
        // These AVCC lengths look like three-byte Annex-B start codes. None may lose a P frame.
        for(int length:new int[]{256,257,300,321,511}) {
            byte[] normalized=ICLinkProtocol.annexB(avccNal(length,0x41));
            check(Arrays.equals(normalized,canonicalNal(length,0x41)));
            check(ICLinkProtocol.hasNal(normalized,1));check(!ICLinkProtocol.isControlOnly(normalized));
        }
        byte[] multi=ICLinkProtocol.concat(avccNal(256,0x41),avccNal(300,0x41));
        check(Arrays.equals(ICLinkProtocol.annexB(multi),
                ICLinkProtocol.concat(canonicalNal(256,0x41),canonicalNal(300,0x41))));
        byte[] mixed={0,0,1,0x67,0x11,0,0,0,1,0x68,0x22,0,0,1,0x65,0x33};
        byte[] canonical={0,0,0,1,0x67,0x11,0,0,0,1,0x68,0x22,0,0,0,1,0x65,0x33};
        check(Arrays.equals(ICLinkProtocol.annexB(mixed),canonical));
        check(Arrays.equals(ICLinkProtocol.annexB(canonical),canonical));
        check(Arrays.equals(ICLinkProtocol.annexB(new byte[]{0,0,0,0,1,0x41,0x55,0,0}),
                new byte[]{0,0,0,1,0x41,0x55}));
        check(ICLinkProtocol.isControlOnly(ICLinkProtocol.annexB(new byte[]{0,0,1,0x67,0x11,0,0,1,0x68,0x22})));
        for(int type:new int[]{6,7,8,9,12})
            check(ICLinkProtocol.isControlOnly(ICLinkProtocol.annexB(avccNal(2,type))));
        // Unsupported extension/partition video and EOS must not be mistaken for ignorable CSD.
        for(int type:new int[]{1,2,3,4,5,10,11,13,14,15,16,17,18,19,20,21,22,23})
            check(!ICLinkProtocol.isControlOnly(ICLinkProtocol.annexB(avccNal(2,type))));
        check(ICLinkProtocol.isControlOnly(ICLinkProtocol.annexB(avccNal(1,9))));
        rejectFraming(new byte[0]);rejectFraming(null);rejectFraming(new byte[ICLinkProtocol.MAX_AU+1]);
        rejectFraming(new byte[]{0,0,0,9,0x41}); // Truncated AVCC.
        rejectFraming(new byte[]{0,0,0,0}); // Zero-length NAL.
        rejectFraming(new byte[]{0,0,1}); // Missing Annex-B header.
        rejectFraming(new byte[]{0,0,1,0x41,0x55,0,0,1}); // Dangling next start code.
        rejectFraming(new byte[]{0,0,1,0x41,0x55,0,0,1,0x68,0,0,1,0x65,(byte)0x80,0,0,1});
        rejectFraming(new byte[]{1,0,0,1,0x41,0x55}); // Leading garbage.
        for(int header:new int[]{0,24,31,0x80,0xe5}) {
            rejectFraming(avccNal(2,header));
            rejectFraming(new byte[]{0,0,1,(byte)header,0x55});
            rejectFraming(ICLinkProtocol.concat(avccNal(2,0x67),avccNal(2,header)));
            rejectFraming(new byte[]{0,0,1,0x67,0x55,0,0,1,(byte)header,0x55});
        }
    }
    public static void main(String[] args)throws Exception{
        framingTests();
        String key="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ICLinkProtocol.hello(out,2,new int[]{640,360,640,360},key);
        byte[] h=out.toByteArray();check(h.length==60);check(h[43]==2);check((h[45]&255)==0);check((h[46]&255)==2);check((h[47]&255)==128);
        byte[] avcc={0,0,0,2,0x67,1,0,0,0,2,0x68,2,0,0,0,2,0x65,3};
        byte[] annex=ICLinkProtocol.annexB(avcc);check(ICLinkProtocol.hasNal(annex,7));check(ICLinkProtocol.hasNal(annex,8));check(ICLinkProtocol.hasNal(annex,5));
        byte[] csd=ICLinkProtocol.config(annex);check(csd.length==12);check(!ICLinkProtocol.hasNal(csd,5));
        check(Arrays.equals(annex,ICLinkProtocol.annexB(annex)));
        out.reset();ICLinkProtocol.video(out,annex,12345);check(out.size()==16+annex.length);
        boolean rejected=false;try{ICLinkProtocol.annexB(new byte[]{0,0,0,9,1});}catch(IOException e){rejected=true;}check(rejected);
        rejected=false;try{ICLinkProtocol.validateCrop(new int[]{Integer.MAX_VALUE,0,5,5});}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        rejected=false;try{ICLinkProtocol.token("short");}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        if(args.length>0)try(FileOutputStream f=new FileOutputStream(args[0])){ICLinkProtocol.hello(f,2,new int[]{640,360,640,360},key);}
        System.out.println("Java protocol tests PASS");
    }
}

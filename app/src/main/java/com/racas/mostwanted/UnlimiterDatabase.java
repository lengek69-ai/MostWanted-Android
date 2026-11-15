package com.racas.mostwanted;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Decoder for Nikki/Unlimiter CAR.bin and PARTS.bin collections. */
final class UnlimiterDatabase {
    private static final int NIKKI=0x42704D67, GAME=2, CAR_TYPES=0x579512F6, MODEL_PARTS=0x768C36C0;
    private UnlimiterDatabase() {}

    static byte[] managerPadding(int offset) {
        int difference = 0x10 - (offset % 0x10) + 0x0C;
        int size = difference + 0x50;
        Out o = new Out();
        o.i32(0);
        o.i32(size - 8);
        o.i32(0x42704D67);
        o.i32(0);
        fixedBlockString(o, "Padding Block", 0x20);
        fixedBlockString(o, "MostWanted Android", 0x20);
        if (o.size() > size) throw new IllegalStateException("padding block overflow");
        o.zeros(size - o.size());
        return o.bytes();
    }

    private static void fixedBlockString(Out o,String s,int n) {
        byte[] b=(s==null?"":s).getBytes(StandardCharsets.UTF_8);
        o.bytes(Arrays.copyOf(b,Math.min(b.length,n-1)));
        o.zeros(n-Math.min(b.length,n-1));
    }

    static String carManufacturer(byte[] file,String car)throws IOException{
        Cursor c=new Cursor(raw(file,CAR_TYPES)); String name=c.str(),make=c.str();
        if(!name.equalsIgnoreCase(car))throw new IOException("CAR.bin belongs to "+name+", not "+car);
        return make.isEmpty()?"LAMBORGHINI":make;
    }

    static byte[] carTypeRecord(byte[] file,String expected,int index,String fallbackMake)throws IOException{
        Cursor c=new Cursor(raw(file,CAR_TYPES)); String name=c.str(),make=c.str();
        if(!name.equalsIgnoreCase(expected))throw new IOException("CAR.bin belongs to "+name+", not "+expected);
        if(make.isEmpty())make=fallbackMake;
        byte[] r=new byte[0xD0];
        fixed(r,0,name,0x10); fixed(r,0x10,name,0x10);
        fixed(r,0x20,"CARS\\"+name+"\\GEOMETRY.BIN",0x20); fixed(r,0x40,make,0x10);
        p32(r,0x50,hash(name)); p32(r,0x54,c.i32()); c.copy(r,0x58,7);
        for(int o=0x60;o<=0x8C;o+=4)p32(r,o,c.i32());
        p32(r,0x90,index); p32(r,0x94,c.i32()); p32(r,0x98,c.i32());
        c.copy(r,0x9C,10);
        for(int o=0xA8;o<=0xB8;o+=4)p32(r,o,c.i32());
        c.copy(r,0xBC,10); r[0xC6]=(byte)c.u8(); r[0xC7]=(byte)c.u8();
        // CAR.bin serialization omits the four-byte in-game padding field at 0xC8.
        p32(r,0xCC,c.i32()); c.end("CAR.bin"); return r;
    }

    static byte[] partsBlock(byte[] file,String expected)throws IOException{
        Cursor c=new Cursor(raw(file,MODEL_PARTS)); String model=c.str();
        if(!model.equalsIgnoreCase(expected))throw new IOException("PARTS.bin belongs to "+model+", not "+expected);
        int n=c.i32(); if(n<1||n>4096)throw new IOException("invalid PARTS.bin part count: "+n);
        List<Part> parts=new ArrayList<>(n); LinkedHashMap<String,Integer> strings=new LinkedHashMap<>();
        strings.put("",1); LinkedHashMap<Integer,Custom> custom=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            int ac=c.i32(); if(ac<0||ac>4096)throw new IOException("invalid attribute count in part "+i);
            Part p=new Part(); p.label=c.str(); p.debug=c.str(); p.group=c.u8(); p.upgrade=c.u16();
            p.struct=readStruct(c); p.attrs=new ArrayList<>(ac);
            for(int j=0;j<ac;j++){
                Attr a=readAttr(c); p.attrs.add(a);
                if(a.custom){
                    Custom old=custom.get(a.key), def=new Custom(a.name,a.key,a.type);
                    if(old!=null&&(!old.name.equals(def.name)||old.type!=def.type))throw new IOException("conflicting custom attribute "+a.name);
                    custom.put(a.key,def);
                    if(a.type==4&&a.exists)intern(strings,a.s1);
                    if(a.type==5){if(a.e1)intern(strings,a.s1);if(a.e2)intern(strings,a.s2);}
                }else if(a.type==4&&a.exists)intern(strings,a.s1);
            }
            intern(strings,p.debug);
            if(p.struct!=null&&p.struct.templated){
                if(p.struct.concatExists)intern(strings,p.struct.concat);
                for(int j=0;j<5;j++)if(p.struct.lodExists[j])intern(strings,p.struct.lod[j]);
            }
            parts.add(p);
        }
        c.end("PARTS.bin");

        Out str=makeStrings(strings), offs=new Out(), attrs=new Out(), structs=new Out(), models=new Out(), array=new Out();
        LinkedHashMap<ByteKey,Integer> attributeIndices=new LinkedHashMap<>();
        Map<List<Integer>,Integer> offsetIndices=new HashMap<>();
        LinkedHashMap<ByteKey,Integer> structIndices=new LinkedHashMap<>();

        for(Part p:parts){
            List<Integer> attributeIds=new ArrayList<>(p.attrs.size());
            for(Attr a:p.attrs){
                Out encoded=new Out();
                writeAttr(encoded,a,strings);
                ByteKey signature=new ByteKey(encoded.bytes());
                Integer index=attributeIndices.get(signature);
                if(index==null){
                    if(attributeIndices.size()>=0xFFFF)throw new IOException("too many unique part attributes");
                    index=attributeIndices.size();
                    attributeIndices.put(signature,index);
                    attrs.bytes(signature.value);
                }
                attributeIds.add(index);
            }

            int attrOffset=0xFFFF;
            if(!attributeIds.isEmpty()){
                Integer known=offsetIndices.get(attributeIds);
                if(known==null){
                    int newOffset=offs.size()/2;
                    if(newOffset>=0xFFFF)throw new IOException("too many unique part attribute offsets");
                    offsetIndices.put(new ArrayList<>(attributeIds),newOffset);
                    offs.u16(attributeIds.size());
                    for(int index:attributeIds)offs.u16(index);
                    attrOffset=newOffset;
                }else attrOffset=known;
            }

            int structOffset=0xFFFF;
            if(p.struct!=null){
                Out encoded=new Out();
                writeStruct(encoded,p.struct,strings);
                ByteKey signature=new ByteKey(encoded.bytes());
                Integer index=structIndices.get(signature);
                if(index==null){
                    if(structIndices.size()>=0xFFFF)throw new IOException("too many unique part structs");
                    index=structIndices.size();
                    structIndices.put(signature,index);
                    structs.bytes(signature.value);
                }
                structOffset=index;
            }

            array.i32(hash(p.label)); array.u8(p.group); array.u16(p.upgrade); array.u8(0);
            array.u16(strIndex(strings,p.debug)); array.u16(attrOffset); array.u16(structOffset);
        }

        int attrCount=attributeIndices.size(), structCount=structIndices.size();
        models.i32(hash(model));
        offs.padFor(0x0C); attrs.padFor(8); structs.padFor(8); models.padFor(4); array.padFor(8);
        Out customs=new Out(); customs.i32(custom.size());
        for(Custom d:custom.values()){customs.str(d.name);customs.i32(d.key);customs.i32(d.type);}
        customs.padFor(8);
        Out head=new Out();head.zeros(0x3C);head.p32(8,6);head.p32(0x20,attrCount);
        head.p32(0x28,1);head.p32(0x30,structCount);head.p32(0x38,n);
        Out payload=new Out();
        chunk(payload,0x34603,head.bytes());chunk(payload,0x34606,str.bytes());chunk(payload,0x3460C,offs.bytes());
        chunk(payload,0x34605,attrs.bytes());chunk(payload,0x3460A,structs.bytes());chunk(payload,0x3460B,models.bytes());
        chunk(payload,0x34604,array.bytes());chunk(payload,0x3460D,customs.bytes());
        // Nikki's DBModelPartManager.Encode writes exactly these eight subchunks. The
        // manager's 0xC actual alignment is supplied by managerPadding() before this root;
        // adding an in-root 0x80 padding chunk changes the native block structure.
        Out root=new Out();root.i32(0x80034602);root.i32(payload.size());root.bytes(payload.bytes());
        return root.bytes();
    }

    private static byte[] raw(byte[] f,int expectedName)throws IOException{
        if(f==null||f.length<36||i32(f,0)!=NIKKI||i32(f,8)!=GAME||i32(f,12)!=expectedName)
            throw new IOException("not the expected Nikki Most Wanted collection");
        int packed=i32(f,16);
        if(packed<16||packed!=f.length-20||i32(f,4)!=packed+12)throw new IOException("invalid serialized file length");
        if(i32(f,20)!=0x57574152||(f[24]&255)!=1||(f[25]&255)!=0x10||i32(f,28)!=packed-16||i32(f,32)!=packed)
            throw new IOException("invalid RAWW wrapper");
        return Arrays.copyOfRange(f,36,f.length);
    }

    private static Struct readStruct(Cursor c)throws IOException{
        if(c.u8()==0)return null;
        Struct s=new Struct();
        s.templated=c.u8()!=0;
        s.concatExists=c.u8()!=0;
        if(s.concatExists)s.concat=c.str();

        // Nikki serializes all five existence flags before any LOD names.
        // Reading a name inside this loop consumes the remaining flags as text,
        // corrupting the first LOD (and every following absent/present LOD pattern).
        for(int i=0;i<5;i++)s.lodExists[i]=c.u8()!=0;
        for(int i=0;i<5;i++)if(s.lodExists[i])s.lod[i]=c.str();
        return s;
    }

    private static Attr readAttr(Cursor c)throws IOException{
        Attr a=new Attr();a.key=c.i32();
        if(BOOL.contains(a.key)){a.type=1;a.value=c.u8();}
        else if(FLOAT.contains(a.key)){a.type=3;a.bits=c.i32();}
        else if(STRING.contains(a.key)){a.type=4;a.exists=c.u8()!=0;if(a.exists)a.s1=c.str();}
        else if(KEY.contains(a.key)){a.type=8;a.s1=c.str();}
        else if(INT.contains(a.key)){a.type=2;a.value=c.i32();}
        else{
            a.custom=true;a.name=c.str();a.type=c.i32();
            switch(a.type){
                case 1:a.value=c.u8();break;
                case 2:a.value=c.i32();break;
                case 3:a.bits=c.i32();break;
                case 4:a.exists=c.u8()!=0;if(a.exists)a.s1=c.str();break;
                case 5:a.e1=c.u8()!=0;a.e2=c.u8()!=0;if(a.e1)a.s1=c.str();if(a.e2)a.s2=c.str();break;
                case 6:a.alpha=c.u8();a.blue=c.u8();a.green=c.u8();a.red=c.u8();break;
                case 8:a.s1=c.str();break;
                default:break; // Nikki Most Wanted consumes no serialized payload for other custom types.
            }
        }
        return a;
    }

    private static void writeAttr(Out o,Attr a,Map<String,Integer> strings)throws IOException{
        o.i32(a.key);
        if(a.custom){
            switch(a.type){
                case 1:o.i32(a.value==0?0:1);break;
                case 2:o.i32(a.value);break;
                case 3:o.i32(a.bits);break;
                case 4:o.i32(a.exists?strIndex(strings,a.s1):-1);break;
                case 5:o.u16(a.e1?strIndex(strings,a.s1):0xFFFF);o.u16(a.e2?strIndex(strings,a.s2):0xFFFF);break;
                case 6:o.u8(a.red);o.u8(a.green);o.u8(a.blue);o.u8(a.alpha);break;
                case 8:o.i32(hash(a.s1));break;
                default:o.i32(0);break; // Matches Nikki CustomAttribute.Assemble default case.
            }
        }else switch(a.type){
            case 1:o.i32(a.value==0?0:1);break;
            case 2:o.i32(a.value);break;
            case 3:o.i32(a.bits);break;
            case 4:o.i32(a.exists?strIndex(strings,a.s1):-1);break;
            case 8:o.i32(hash(a.s1));break;
            default:throw new IOException("unsupported standard attribute type "+a.type);
        }
    }

    private static void writeStruct(Out o,Struct s,Map<String,Integer> strings)throws IOException{
        if(s.templated){
            o.u16(1);o.u16(s.concatExists?strIndex(strings,s.concat):0xFFFF);
            for(int i=0;i<5;i++)o.i32(s.lodExists[i]?strIndex(strings,s.lod[i]):-1);
        }else{
            o.i32(0xFFFF0000);for(int i=0;i<5;i++)o.i32(s.lodExists[i]?hash(s.lod[i]):-1);
        }
    }

    private static Out makeStrings(LinkedHashMap<String,Integer> map)throws IOException{
        Out o=new Out();o.i32(0);o.i32(0);byte[] mark="MostWanted Android".getBytes(StandardCharsets.US_ASCII);
        o.bytes(mark);o.zeros(0x20-mark.length);
        for(Map.Entry<String,Integer> e:map.entrySet()){
            if(e.getKey().isEmpty())continue;
            if(o.size()!=e.getValue()*4)throw new IOException("string table offset mismatch");
            o.str(e.getKey());o.align(4);
        }
        o.align(0x10);return o;
    }
    private static int intern(LinkedHashMap<String,Integer> map,String value)throws IOException{
        if(value==null)value="";Integer old=map.get(value);if(old!=null)return old;
        int pos=0x28;for(String s:map.keySet())if(!s.isEmpty())pos+=(s.getBytes(StandardCharsets.UTF_8).length+4)&~3;
        if((pos&3)!=0||pos/4>0xFFFF)throw new IOException("string table exceeds index range");
        int idx=pos/4;map.put(value,idx);return idx;
    }
    private static int strIndex(Map<String,Integer> map,String s)throws IOException{
        Integer i=map.get(s==null?"":s);if(i==null)throw new IOException("missing string table value");return i;
    }
    private static void chunk(Out o,int id,byte[] b){o.i32(id);o.i32(b.length);o.bytes(b);}
    private static int hash(String s){return s==null||s.trim().isEmpty()?0:CarConverter.bhash(s);}
    private static Set<Integer> set(int... a){Set<Integer>s=new HashSet<>();for(int n:a)s.add(n);return s;}
    private static int i32(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
    private static void p32(byte[]b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>>8);b[p+2]=(byte)(v>>>16);b[p+3]=(byte)(v>>>24);}
    private static void fixed(byte[]b,int p,String s,int n){byte[]a=(s==null?"":s).getBytes(StandardCharsets.US_ASCII);System.arraycopy(a,0,b,p,Math.min(n-1,a.length));}

    private static final Set<Integer> BOOL=set(0x03B83203,0x039DD714,0x70FBB1E4,0x545C3440,0x6BFA56DA,0xCDAB2874,0x6B66D0E0,0x7C811574,0x0D128B87,0x3DA5ADAC,0x6AB42ADF,0xC1A84E52,0x09163F9F,0x09163FA0,0x87557E1E,0x6509EC92,0x721AFF7C,0xF7933C86,0xF7934315,0xF7934316,0xF9661A07,0x1BC91595,0x1BC91597,0x1BC91598,0x1BC9159A,0x1BC9159B,0x1BC915B5,0x1BC915BD,0x6DB4AF51);
    private static final Set<Integer> FLOAT=set(0x02800544,0x7822E22B,0x7822F337,0x78230E71,0x06159D55,0x0615AE61,0x0615C99B,0x9A9B6DDC,0xA0773FA5);
    private static final Set<Integer> STRING=set(0xFD35FE70,0x7D65A926,0x46B79643);
    private static final Set<Integer> KEY=set(0x000004B8,0x00194031,0x00194032,0x00194033,0x00194034,0x00194035,0x00194036,0x00194037,0x00194038,0x0019CBC0,0x001CAD5A,0x03B16390,0x7AED5629,0xF073C523,0xF073C524,0x0D4B85C7,0xEBB03E66,0xEDB20048,0xBCADE4C3,0x8C185134,0xE80A3B62,0x4732DA07,0xD68A7BAB,0x10C98090,0xEDBF864E,0x8E73B5DC,0x931FF82E,0x29008B14,0x6223C6F9,0x65F58556,0xB5548ED7,0x04B39858,0x5412A1D9,0x6BA02C05,0x900449D3,0x956326AF);
    private static final Set<Integer> INT=set(0x0000D99A,0x001C0D0C,0x00136707,0x026E1AC5,0x02DDC8F0,0x02DAAB07,0x02804819,0x50317397,0x5E96E722,0xD90F9423,0x368A1A6A,0x07C4C1D7,0x311151F8,0x38A30E75,0x00BA7DC0,0x796C0CB0,0x564B8CB6,0xA77BDCFA,0x7D29CF3E,0x48620C16,0x1B0EA1A9,0xEB0101E2,0xCE7D8DB5,0xC9818DFC,0x6212682B,0x2850A03B,0x927097F6,0x643DABEB);

    private static final class Cursor{
        final byte[]b;int p;Cursor(byte[]b){this.b=b;}
        void need(int n)throws IOException{if(n<0||p+(long)n>b.length)throw new IOException("truncated Unlimiter data");}
        int u8()throws IOException{need(1);return b[p++]&255;}
        int u16()throws IOException{int x=u8();return x|(u8()<<8);}
        int i32()throws IOException{need(4);int x=UnlimiterDatabase.i32(b,p);p+=4;return x;}
        String str()throws IOException{int a=p;while(p<b.length&&b[p]!=0)p++;if(p==b.length)throw new IOException("unterminated Unlimiter string");String s=new String(b,a,p-a,StandardCharsets.UTF_8);p++;return s;}
        void copy(byte[]d,int q,int n)throws IOException{need(n);System.arraycopy(b,p,d,q,n);p+=n;}
        void skip(int n)throws IOException{need(n);p+=n;}
        void end(String f)throws IOException{if(p!=b.length)throw new IOException(f+" has "+(b.length-p)+" trailing bytes");}
    }
    private static final class Out{
        final ByteArrayOutputStream b=new ByteArrayOutputStream();int size(){return b.size();}
        void u8(int x){b.write(x);}void u16(int x){u8(x);u8(x>>>8);}
        void i32(int x){u8(x);u8(x>>>8);u8(x>>>16);u8(x>>>24);}
        void bytes(byte[]x){b.write(x,0,x.length);}void str(String s){bytes((s==null?"":s).getBytes(StandardCharsets.UTF_8));u8(0);}
        void zeros(int n){for(int i=0;i<n;i++)u8(0);}void align(int n){while(size()%n!=0)u8(0);}
        void padFor(int base){zeros((16-((size()+base)&15))&15);}
        void p32(int p,int v){byte[]a=b.toByteArray();UnlimiterDatabase.p32(a,p,v);b.reset();bytes(a);}
        byte[]bytes(){return b.toByteArray();}
    }
    private static final class ByteKey{
        final byte[] value; final int hash;
        ByteKey(byte[] value){this.value=value.clone();this.hash=Arrays.hashCode(this.value);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return other instanceof ByteKey&&Arrays.equals(value,((ByteKey)other).value);}
    }
    private static final class Part{String label,debug;int group,upgrade;Struct struct;List<Attr>attrs;}
    private static final class Struct{boolean templated,concatExists;String concat;boolean[]lodExists=new boolean[5];String[]lod=new String[5];}
    private static final class Attr{int key,type,value,bits,alpha,blue,green,red;boolean custom,exists,e1,e2;String name,s1,s2;}
    private static final class Custom{final String name;final int key,type;Custom(String n,int k,int t){name=n;key=k;type=t;}}
}

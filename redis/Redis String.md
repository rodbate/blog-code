## Redis 字符串数据结构(String)底层存储源码分析

> 以下涉及到的源码均来自 https://github.com/redis/redis (branch 6.2)



### 背景知识

**在6.2这个版本redis支持的数据结构如下**

```c
<server.h>
/* The actual Redis Object */
#define OBJ_STRING 0    /* String object. */
#define OBJ_LIST 1      /* List object. */
#define OBJ_SET 2       /* Set object. */
#define OBJ_ZSET 3      /* Sorted set object. */
#define OBJ_HASH 4      /* Hash object. */

#define OBJ_MODULE 5    /* Module object. */
#define OBJ_STREAM 6    /* Stream object. */
```

> 可能大家比较熟悉和用的比较多的是前5种数据结构(后面两种是后来加入的)



**redis数据结构编码类型**

```c
#define OBJ_ENCODING_RAW 0     /* Raw representation */
#define OBJ_ENCODING_INT 1     /* Encoded as integer */
#define OBJ_ENCODING_HT 2      /* Encoded as hash table */
#define OBJ_ENCODING_ZIPMAP 3  /* Encoded as zipmap */
#define OBJ_ENCODING_LINKEDLIST 4 /* No longer used: old list encoding. */
#define OBJ_ENCODING_ZIPLIST 5 /* Encoded as ziplist */
#define OBJ_ENCODING_INTSET 6  /* Encoded as intset */
#define OBJ_ENCODING_SKIPLIST 7  /* Encoded as skiplist */
#define OBJ_ENCODING_EMBSTR 8  /* Embedded sds string encoding */
#define OBJ_ENCODING_QUICKLIST 9 /* Encoded as linked list of ziplists */
#define OBJ_ENCODING_STREAM 10 /* Encoded as a radix tree of listpacks */
```

> 其中字符串涉及到的编码类型有 
>
> OBJ_ENCODING_RAW(原生,)
>
> OBJ_ENCODING_INT(整型)
>
> OBJ_ENCODING_EMBSTR(紧凑型, 前提条件字符串长度小于等于44，后面再分析这个数字是如何来的)



这些数据结构在redis内部表示

```c
<server.h>
#define LRU_BITS 24

typedef struct redisObject {
    //数据结构类型(如上所述)
    unsigned type:4;
    //编码格式(如上所述 如何存储在内存)
    unsigned encoding:4;
    //缓存策略数据
    unsigned lru:LRU_BITS; /* LRU time (relative to global lru_clock) or
                            * LFU data (least significant 8 bits frequency
                            * and most significant 16 bits access time). */
    int refcount;//结构体引用数
    void *ptr;//实际的数据
} robj;
```

> 从redisObject可以算出该结构体占用内存大小
>
> 4bit + 4bit + 24bit + 4Byte + 4Byte = 12Byte
>
> 即是 sizeof(struct redisObject) = 12 (后面有用到)





### Redis字符串(string)内部数据结构Sds

```c
<sds.h>
typedef char *sds;
```

> 从上面看起来sds和普通字符串没啥区别, 其实redis为了节省内存做了更细的划分

```c
<sds.h>

//sds类型
#define SDS_TYPE_5  0
#define SDS_TYPE_8  1
#define SDS_TYPE_16 2
#define SDS_TYPE_32 3
#define SDS_TYPE_64 4
    
//适用于len<2^5
struct __attribute__ ((__packed__)) sdshdr5 {
    //高5位表示字符串长度, 低三位表示sds类型
    unsigned char flags; /* 3 lsb of type, and 5 msb of string length */
    //实际的字符串数据
    char buf[];
};

//适用于2^5<=len<2^8
struct __attribute__ ((__packed__)) sdshdr8 {
    uint8_t len; /* used */
    uint8_t alloc; /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};

//适用于2^8<=len<2^16
struct __attribute__ ((__packed__)) sdshdr16 {
    uint16_t len; /* used */
    uint16_t alloc; /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};

//适用于2^16<=len<2^32
struct __attribute__ ((__packed__)) sdshdr32 {
    uint32_t len; /* used */
    uint32_t alloc; /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};

//适用于2^32<=len
struct __attribute__ ((__packed__)) sdshdr64 {
    uint64_t len; /* used */
    uint64_t alloc; /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};
```

> 除了sdshdr5的结构不太一样，其它结构都一样(适用于不同长度的字符串)
>
> len -> 已使用的长度
>
> alloc -> 分配的长度(除去了sds头和空'\0'结束符 - 可以更方便的计算可用长度 avail = alloc - len)
>
> flags -> 低三位表示sds类型, 高五位目前还未使用到
>
> buf[] -> 实际字符串数据



> sds header里存有len长度的一个解释
>
> You can print the string with printf() as there is an implicit \0 at the end of the string. However the string is binary safe and can contain \0 characters in the middle, as the length is stored in the sds header.



来看看sds的创建过程就能大概明白了



#### sds创建

```c
<sds.c>
//创建sds的函数
sds _sdsnewlen(const void *init, size_t initlen, int trymalloc) {
    void *sh;
    sds s;
    //1. 根据长度来确定sds类型
    char type = sdsReqType(initlen);
    /* Empty strings are usually created in order to append. Use type 8
     * since type 5 is not good at this. */
    if (type == SDS_TYPE_5 && initlen == 0) type = SDS_TYPE_8;
    //2. 计算sds header大小
    int hdrlen = sdsHdrSize(type);
    unsigned char *fp; /* flags pointer. */
    size_t usable;
	
    //3. 分配内存
    sh = trymalloc?
        s_trymalloc_usable(hdrlen+initlen+1, &usable) :
        s_malloc_usable(hdrlen+initlen+1, &usable);
    
    //4. 内存初始化
    if (sh == NULL) return NULL;
    if (init==SDS_NOINIT)
        init = NULL;
    else if (!init)
        memset(sh, 0, hdrlen+initlen+1);
    
    //5. sds结构设置
    s = (char*)sh+hdrlen;
    fp = ((unsigned char*)s)-1;
    usable = usable-hdrlen-1;
    if (usable > sdsTypeMaxSize(type))
        usable = sdsTypeMaxSize(type);
    switch(type) {
        case SDS_TYPE_5: {
            *fp = type | (initlen << SDS_TYPE_BITS);
            break;
        }
        case SDS_TYPE_8: {
            SDS_HDR_VAR(8,s);
            sh->len = initlen;
            sh->alloc = usable;
            *fp = type;
            break;
        }
        case SDS_TYPE_16: {
            SDS_HDR_VAR(16,s);
            sh->len = initlen;
            sh->alloc = usable;
            *fp = type;
            break;
        }
        case SDS_TYPE_32: {
            SDS_HDR_VAR(32,s);
            sh->len = initlen;
            sh->alloc = usable;
            *fp = type;
            break;
        }
        case SDS_TYPE_64: {
            SDS_HDR_VAR(64,s);
            sh->len = initlen;
            sh->alloc = usable;
            *fp = type;
            break;
        }
    }
    if (initlen && init)
        memcpy(s, init, initlen);
    
    //设置字符串终止符
    s[initlen] = '\0';
    return s;
}
```





##### 1. 如何确定新建的sds类型

```c
<sds.c>
//根据长度来确定sds类型
static inline char sdsReqType(size_t string_size) {
    if (string_size < 1<<5)
        return SDS_TYPE_5;
    if (string_size < 1<<8)
        return SDS_TYPE_8;
    if (string_size < 1<<16)
        return SDS_TYPE_16;
#if (LONG_MAX == LLONG_MAX) //64位系统
    if (string_size < 1ll<<32)
        return SDS_TYPE_32;
    return SDS_TYPE_64;
#else //32位系统
    return SDS_TYPE_32;
#endif
}
```



##### 2. 计算sds header大小

```c
<sds.c>
#define SDS_TYPE_MASK 7
//根据已确定的sds类型来计算sds header大小
static inline int sdsHdrSize(char type) {
    //掩码位运算, 低三位表示sds类型
    switch(type&SDS_TYPE_MASK) {
        case SDS_TYPE_5:
            return sizeof(struct sdshdr5);
        case SDS_TYPE_8:
            return sizeof(struct sdshdr8);
        case SDS_TYPE_16:
            return sizeof(struct sdshdr16);
        case SDS_TYPE_32:
            return sizeof(struct sdshdr32);
        case SDS_TYPE_64:
            return sizeof(struct sdshdr64);
    }
    return 0;
}
```



##### 3. 分配内存

```c
<sds.c>
//分配的内存大小 = sds header len + initlen(实际字符串长度) + 1(终止符\0)
sh = trymalloc?
        s_trymalloc_usable(hdrlen+initlen+1, &usable) :
        s_malloc_usable(hdrlen+initlen+1, &usable);

<zmalloc.c>
#没有足够的内存可分配会返回NULL,程序继续正常运行
void *ztrymalloc_usable(size_t size, size_t *usable) {
    void *ptr = malloc(size+PREFIX_SIZE);

    if (!ptr) return NULL;
#ifdef HAVE_MALLOC_SIZE
    size = zmalloc_size(ptr);
    update_zmalloc_stat_alloc(size);
    if (usable) *usable = size;
    return ptr;
#else
    *((size_t*)ptr) = size;
    update_zmalloc_stat_alloc(size+PREFIX_SIZE);
    if (usable) *usable = size;
    return (char*)ptr+PREFIX_SIZE;
#endif
}

#没有足够的内存课可分配就会程序终止
void *zmalloc_usable(size_t size, size_t *usable) {
    void *ptr = ztrymalloc_usable(size, usable);
    if (!ptr) zmalloc_oom_handler(size);
    return ptr;
}
static void zmalloc_default_oom(size_t size) {
    fprintf(stderr, "zmalloc: Out of memory trying to allocate %zu bytes\n",
        size);
    fflush(stderr);
    abort();
}

```



##### 4. 内存初始化

```c
const char *SDS_NOINIT = "SDS_NOINIT";
if (sh == NULL) return NULL; //内存分配失败直接返回NULL
if (init==SDS_NOINIT) //init为SDS_NOINIT, 就不进行初始化
    init = NULL;
else if (!init) //init为NULL，就初始化长度为0
    memset(sh, 0, hdrlen+initlen+1);
//这里的整个连续内存大小=sds header + initlen(实际字符串长度) + 1(终止符\0)
```



##### 5. sds结构设置

```c
//实际字符串开始指针
s = (char*)sh+hdrlen;
//sds flags开始指针
fp = ((unsigned char*)s)-1;
//字符串可用长度 = 分配的总长度 - sds header len - 1(终止符\0)
usable = usable-hdrlen-1;

//可用长度边界调整
if (usable > sdsTypeMaxSize(type))
    usable = sdsTypeMaxSize(type);

//以下都是设置sds -> len, alloc, flags
switch(type) {
    case SDS_TYPE_5: {
        *fp = type | (initlen << SDS_TYPE_BITS);
        break;
    }
    case SDS_TYPE_8: {
        SDS_HDR_VAR(8,s);
        sh->len = initlen;
        sh->alloc = usable;
        *fp = type;
        break;
    }
    case SDS_TYPE_16: {
        SDS_HDR_VAR(16,s);
        sh->len = initlen;
        sh->alloc = usable;
        *fp = type;
        break;
    }
    case SDS_TYPE_32: {
        SDS_HDR_VAR(32,s);
        sh->len = initlen;
        sh->alloc = usable;
        *fp = type;
        break;
    }
    case SDS_TYPE_64: {
        SDS_HDR_VAR(64,s);
        sh->len = initlen;
        sh->alloc = usable;
        *fp = type;
        break;
    }
}
if (initlen && init)
    memcpy(s, init, initlen);//将数据拷贝到sds(即struct sds -> buf)

//设置字符串终止符
s[initlen] = '\0';
return s;

//根据sds类型计算最大可存储字符串长度
static inline size_t sdsTypeMaxSize(char type) {
    if (type == SDS_TYPE_5)
        return (1<<5) - 1;
    if (type == SDS_TYPE_8)
        return (1<<8) - 1;
    if (type == SDS_TYPE_16)
        return (1<<16) - 1;
#if (LONG_MAX == LLONG_MAX)
    if (type == SDS_TYPE_32)
        return (1ll<<32) - 1;
#endif
    return -1; /* this is equivalent to the max SDS_TYPE_64 or SDS_TYPE_32 */
}
```



> 从上面sds创建过程可以大概的了解到sds在内存的存储形式
>
> 连续的内存, 可依次分为sds header + sds buf(字符串数据) + '\0'(终止符)



**redis的字符串结构体redisObject的ptr指向就是sds结构**

> 那接下来就来看看struct redisObject(也就是robj)在内存的存储形式，了解完后就能对一个字符串是如何在redis中存储的就会比较清晰了



### redisObject(robj)内存存储形式

再来回顾下这个结构

```c
<server.h>
typedef struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:LRU_BITS; /* LRU time (relative to global lru_clock) or
                            * LFU data (least significant 8 bits frequency
                            * and most significant 16 bits access time). */
    int refcount;
    void *ptr; //针对字符串sds的相关内容已在上面
} robj;
```



**简单看看robj的创建过程**

```c
//比较简单，之后会再对这个robj做进一步处理(针对不同的底层数据结构),比如设置不同的encoding等等
robj *createObject(int type, void *ptr) {
    robj *o = zmalloc(sizeof(*o));
    o->type = type;
    o->encoding = OBJ_ENCODING_RAW;
    o->ptr = ptr;
    o->refcount = 1;

    /* Set the LRU to the current lruclock (minutes resolution), or
     * alternatively the LFU counter. */
    if (server.maxmemory_policy & MAXMEMORY_FLAG_LFU) {
        o->lru = (LFUGetTimeInMinutes()<<8) | LFU_INIT_VAL;
    } else {
        o->lru = LRU_CLOCK();
    }
    return o;
}
```





#### Redis是如何确定字符串的编码类型(robj->encoding)的？

首先从string的set command入手看看

```c
<t_string.c>
void setCommand(client *c) {
    .... //其它的就不关注了
        
    c->argv[2] = tryObjectEncoding(c->argv[2]); //根据输入的字符串来确定字符串的编码类型
    
    ....
}
```



##### OBJ_ENCODING_INT编码类型

```c
<object.c>
robj *tryObjectEncoding(robj *o) {
 ....
     /* Check if we can represent this string as a long integer.
     * Note that we are sure that a string larger than 20 chars is not
     * representable as a 32 nor 64 bit integer. */
     //超过了20位的字符不可能是整型数据
     //string2l: 字符串转整型
    len = sdslen(s);
    if (len <= 20 && string2l(s,len,&value)) {
        if ((server.maxmemory == 0 ||
            !(server.maxmemory_policy & MAXMEMORY_FLAG_NO_SHARED_INTEGERS)) &&
            value >= 0 &&
            value < OBJ_SHARED_INTEGERS)
        {
            //这里是关于全局共享整数的，就不看了
            decrRefCount(o);
            incrRefCount(shared.integers[value]);
            return shared.integers[value];
        } else {
            if (o->encoding == OBJ_ENCODING_RAW) {
                sdsfree(o->ptr);
                o->encoding = OBJ_ENCODING_INT; //确定OBJ_ENCODING_INT编码类型
                o->ptr = (void*) value;
                return o;
            } else if (o->encoding == OBJ_ENCODING_EMBSTR) {
                decrRefCount(o);
                //确定OBJ_ENCODING_INT编码类型，这里就不继续往里看了
                return createStringObjectFromLongLongForValue(value);
            }
        }
    }
}
```



##### OBJ_ENCODING_EMBSTR

```c
<object.c>
#define OBJ_ENCODING_EMBSTR_SIZE_LIMIT 44
if (len <= OBJ_ENCODING_EMBSTR_SIZE_LIMIT) {
    robj *emb;

    if (o->encoding == OBJ_ENCODING_EMBSTR) return o;
    emb = createEmbeddedStringObject(s,sdslen(s));
    decrRefCount(o);
    return emb;
}

trimStringObjectIfNeeded(o);

#其它的就都是OBJ_ENCODING_RAW类型了
/* Return the original object. */
return o;
```





#### 字符串对象(StringObject)创建过程

```c
//整型编码的就不看了(直接将得到的整型&lvalue设置到robj->ptr, robj->ptr = &lvalue)
<object.c>
#define OBJ_ENCODING_EMBSTR_SIZE_LIMIT 44
robj *createStringObject(const char *ptr, size_t len) {
    if (len <= OBJ_ENCODING_EMBSTR_SIZE_LIMIT)
        return createEmbeddedStringObject(ptr,len);
    else
        return createRawStringObject(ptr,len);
}
```



##### 1. OBJ_ENCODING_EMBSTR_SIZE_LIMIT是如何计算出来的

看看redis的注释

>The current limit of 44 is chosen so that the biggest string object 
>
>we allocate as EMBSTR will still fit into the 64 byte arena of jemalloc.
>
>redis使用的内存分配库的申请内存快单位是64byte, 由于embstr的整个redisObject是设计成一块连续内存的(提升读写效率), 因此embstr的整个redisObject大小就被限制在64byte, 那再来计算实际字符串的可用内存大小。
>
>开篇就计算了整个struct redisObject大小是12byte
>
>64 - 12 - 7(sizeof(sdshdr8)) - 1(\0) = 44byte



##### 2. Embedded String创建

```c
<object.c>
robj *createEmbeddedStringObject(const char *ptr, size_t len) {
    //分配一块连续内存
    //robj大小 = sizeof(robj) + sizeof(struct sdshdr8) + len(字符串长度) + 1(\0)
    robj *o = zmalloc(sizeof(robj)+sizeof(struct sdshdr8)+len+1);
    struct sdshdr8 *sh = (void*)(o+1);

    o->type = OBJ_STRING; //设置string数据结构
    o->encoding = OBJ_ENCODING_EMBSTR; //设置EMBSTR编码格式
    o->ptr = sh+1;//设置ptr
    o->refcount = 1;//引用计数
    //缓存策略数据
    if (server.maxmemory_policy & MAXMEMORY_FLAG_LFU) {
        o->lru = (LFUGetTimeInMinutes()<<8) | LFU_INIT_VAL;
    } else {
        o->lru = LRU_CLOCK();
    }

    //sds设置
    sh->len = len;
    sh->alloc = len;
    sh->flags = SDS_TYPE_8;
    if (ptr == SDS_NOINIT)
        sh->buf[len] = '\0';
    else if (ptr) {
        memcpy(sh->buf,ptr,len);
        sh->buf[len] = '\0';
    } else {
        memset(sh->buf,0,len+1);
    }
    return o;
}
```



##### 3. Raw String创建

```c
//sdsnewlen(ptr,len)创建sds 再创建robj
robj *createRawStringObject(const char *ptr, size_t len) {
    return createObject(OBJ_STRING, sdsnewlen(ptr,len));
}
```

### Redis-Cli查看robj type encoding

> set abc "1"
>
> type abc -> string
>
> object encoding -> int

> set abc "1a"
>
> type abc -> string
>
> object encoding -> embstr
// lzxun <in.lzx> <out> <out_size> <window_size> : raw XEX LZX stream -> bytes (libmspack lzxd)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mspack.h"
#include "lzx.h"
typedef struct { unsigned char *b; off_t n, o; } mf;
static int rd(struct mspack_file *f, void *buf, int c){ mf*m=(mf*)f; off_t r=m->n-m->o; if(c<r) r=c; memcpy(buf,m->b+m->o,r); m->o+=r; return (int)r; }
static int wr(struct mspack_file *f, void *buf, int c){ mf*m=(mf*)f; off_t r=m->n-m->o; if(c<r) r=c; memcpy(m->b+m->o,buf,r); m->o+=r; return (int)r; }
static void *al(struct mspack_system *s, size_t n){ return calloc(n,1); }
static void fr(void *p){ free(p); }
static void cp(void *s, void *d, size_t n){ memcpy(d,s,n); }
static void msg(struct mspack_file *f, const char *fmt, ...){ }
int main(int argc,char**argv){
  FILE*f=fopen(argv[1],"rb"); fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
  unsigned char*in=malloc(n); fread(in,1,n,f); fclose(f);
  off_t outn=strtol(argv[3],0,0); unsigned win=strtoul(argv[4],0,0); int bits=0; while((1u<<bits)<win) bits++;
  unsigned char*out=calloc(outn,1);
  struct mspack_system sys={0}; sys.read=rd; sys.write=wr; sys.alloc=al; sys.free=fr; sys.copy=cp; sys.message=msg;
  mf src={in,n,0}, dst={out,outn,0};
  struct lzxd_stream*z=lzxd_init(&sys,(struct mspack_file*)&src,(struct mspack_file*)&dst,bits,0,0x8000,outn,0);
  int rc=lzxd_decompress(z,outn); lzxd_free(z);
  f=fopen(argv[2],"wb"); fwrite(out,1,outn,f); fclose(f);
  fprintf(stderr,"rc=%d written=%ld\n",rc,(long)dst.o); return rc;
}

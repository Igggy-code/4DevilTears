// arcx — MT Framework .arc (Xbox 360, v17) extractor. XMemCompress (LZX, 128K window) via libmspack.
// Usage: arcx <file.arc> <out_dir> [--list]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sys/stat.h>
#include <errno.h>
#include "mspack.h"
#include "lzx.h"

typedef struct { unsigned char *b; off_t n, o; } mf;
static int rd(struct mspack_file *f, void *buf, int c){ mf*m=(mf*)f; off_t r=m->n-m->o; if(c<r) r=c; if(r<0) r=0; memcpy(buf,m->b+m->o,r); m->o+=r; return (int)r; }
static int wr(struct mspack_file *f, void *buf, int c){ mf*m=(mf*)f; off_t r=m->n-m->o; if(c<r) r=c; if(r<0) r=0; memcpy(m->b+m->o,buf,r); m->o+=r; return (int)r; }
static void *al(struct mspack_system *s, size_t n){ (void)s; return calloc(n,1); }
static void fr(void *p){ free(p); }
static void cp(void *s, void *d, size_t n){ memcpy(d,s,n); }
static void msg(struct mspack_file *f, const char *fmt, ...){ (void)f;(void)fmt; }
static uint32_t be32(const unsigned char*p){ return (uint32_t)p[0]<<24|p[1]<<16|p[2]<<8|p[3]; }
static uint16_t be16(const unsigned char*p){ return (uint16_t)(p[0]<<8|p[1]); }

// XMemCompress native stream: frames of [u16 csize] (usize=0x8000) or [0xFF u16 usize u16 csize]
int xmem_decompress(const unsigned char*in, size_t n, unsigned char*out, size_t outn){
  unsigned char*raw=malloc(n); size_t rn=0, p=0;
  while(p+2<=n){
    size_t cs;
    if(in[p]==0xFF){ if(p+5>n) break; cs=be16(in+p+3); p+=5; } else { cs=be16(in+p); p+=2; }
    if(!cs || p+cs>n) break;
    memcpy(raw+rn,in+p,cs); rn+=cs; p+=cs;
  }
  struct mspack_system sys={0}; sys.read=rd; sys.write=wr; sys.alloc=al; sys.free=fr; sys.copy=cp; sys.message=msg;
  mf src={raw,(off_t)rn,0}, dst={out,(off_t)outn,0};
  struct lzxd_stream*z=lzxd_init(&sys,(struct mspack_file*)&src,(struct mspack_file*)&dst,17,0,0x8000,(off_t)outn,0);
  int rc=z?lzxd_decompress(z,(off_t)outn):-1; if(z) lzxd_free(z); free(raw);
  return rc;
}

static void mkdirs(char*path){
  for(char*s=path+1;*s;s++) if(*s=='/'){ *s=0; mkdir(path,0755); *s='/'; }
}
static const char* ext_for(const unsigned char*d, size_t n){
  static char e[8];
  if(n<4) return "bin";
  // MT resources start with a 4-char magic stored as LE u32 on a BE platform -> reversed ("\0XET" = TEX)
  char m[5]={0}; int k=0;
  for(int i=3;i>=0;i--){ unsigned char c=d[i]; if(c>='A'&&c<='Z'){ m[k++]=c+32; } else if(c>='a'&&c<='z'||(c>='0'&&c<='9')) m[k++]=c; }
  if(k>=3){ snprintf(e,sizeof e,"%s",m); return e; }
  return "bin";
}

int main(int argc,char**argv){
  if(argc<3){ fprintf(stderr,"usage: arcx <file.arc> <out_dir> [--list]\n"); return 2; }
  int list = argc>3 && !strcmp(argv[3],"--list");
  FILE*f=fopen(argv[1],"rb"); if(!f){ perror(argv[1]); return 1; }
  fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
  unsigned char*d=malloc(n); if(fread(d,1,n,f)!=(size_t)n){ perror("read"); return 1; } fclose(f);
  if(memcmp(d,"\0CRA",4)){ fprintf(stderr,"%s: not an Xbox 360 ARC\n",argv[1]); return 1; }
  int ver=be16(d+4), cnt=be16(d+6), bad=0;
  if(ver!=17) fprintf(stderr,"warning: ARC version %d\n",ver);
  for(int i=0;i<cnt;i++){
    const unsigned char*e=d+8+i*80;
    char name[65]; memcpy(name,e,64); name[64]=0;
    uint32_t th=be32(e+64), cs=be32(e+68), dsf=be32(e+72), off=be32(e+76);
    uint32_t ds=dsf>>3, flags=dsf&7;
    if(list){ printf("%08X %9u %9u %u %s\n",th,cs,ds,flags,name); continue; }
    unsigned char*out=malloc(ds?ds:1); int rc=0;
    if(cs==ds) memcpy(out,d+off,ds); else rc=xmem_decompress(d+off,cs,out,ds);
    if(rc){ fprintf(stderr,"FAIL rc=%d %s\n",rc,name); bad++; free(out); continue; }
    for(char*s=name;*s;s++) if(*s=='\\') *s='/';
    char path[1024]; snprintf(path,sizeof path,"%s/%s.%s",argv[2],name,ext_for(out,ds));
    mkdirs(path); FILE*o=fopen(path,"wb"); if(!o){ perror(path); bad++; free(out); continue; }
    fwrite(out,1,ds,o); fclose(o); free(out);
  }
  if(!list) fprintf(stderr,"%s: %d entries, %d failed\n",argv[1],cnt,bad);
  free(d); return bad?1:0;
}

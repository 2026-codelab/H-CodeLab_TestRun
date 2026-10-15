#include <stdio.h>
#include <string.h>
static char buf[400u*1024*1024];
int main(void){memset(buf,1,sizeof buf);int a,b;scanf("%d %d",&a,&b);printf("%d\n",a+b+buf[12345]-1);return 0;}

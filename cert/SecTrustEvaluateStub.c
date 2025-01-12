#include <stdio.h>
#include <stdarg.h>
#include <Security/Security.h>


OSStatus SecTrustEvaluateStub(SecTrustRef trust, SecTrustResultType *result) {
  *result = kSecTrustResultUnspecified;
  return 0;
}

__attribute__((used)) static struct { const void *replacement; const void *replacee; } _interpose_SecTrustEvaluate
__attribute__ ((section ("__DATA,__interpose"))) = { (const void *)(unsigned long)&SecTrustEvaluateStub, (const void *)(unsigned long)&SecTrustEvaluate };

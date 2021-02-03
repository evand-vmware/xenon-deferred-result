# What is Xenon?

If you want to know what Xenon is, I suggest you take a look at the [main Xenon repo](https://github.com/vmware-archive/xenon)

# What is DeferredResult?

I dont like Xenon, and aparently neither does VMware because they've decided to stop supporting the project. There is one thing I do like about Xenon; its wrapper on CompletableFuture called DeferredResult. The two are VERY similar but there are a few tweaks that I prefer over the vanilla CompletableFuture. 

When starting a new project, I dont want to incorportate Xenon, but it would be nice to pull in just DeferredResults. That is why I decided to (on my own time) fork Xenon and delete EVERYTHING except for DeferredResults. 
interface D1Result<T=unknown> { success:boolean; results:T[]; meta:{changes:number; duration:number; [key:string]:any} }
interface D1PreparedStatement { bind(...values:any[]):D1PreparedStatement; first<T=unknown>():Promise<T|null>; all<T=unknown>():Promise<D1Result<T>>; run<T=unknown>():Promise<D1Result<T>> }
interface D1Database { prepare(query:string):D1PreparedStatement; batch<T=unknown>(statements:D1PreparedStatement[]):Promise<D1Result<T>[]> }
interface ScheduledController { scheduledTime:number; cron:string }
interface ExecutionContext { waitUntil(promise:Promise<any>):void }

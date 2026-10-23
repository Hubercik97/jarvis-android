// Require a complete phrase score on consecutive frames; never substring matching.
export class WakeGate{
 sensitivity=.5;private hits=0;private last=-Infinity;private previous=-Infinity;
 reset(){this.hits=0;this.previous=-Infinity;}
 // Calibrated synthetic-test default is .90. Higher sensitivity lowers the
 // threshold toward .83 and increases the risk of accidental activation.
 get threshold(){const sensitivity=Number.isFinite(this.sensitivity)?this.sensitivity:.5;return .97-Math.max(0,Math.min(1,sensitivity))*.14;}
 accept(score:number,now:number){
  if(!Number.isFinite(score)||score<0||score>1||score<this.threshold||!Number.isFinite(now)||now-this.last<4000){this.reset();return false;}
  if(now-this.previous>400)this.hits=0;
  this.previous=now;
  if(++this.hits<2)return false;
  this.hits=0;this.last=now;return true;
 }
}

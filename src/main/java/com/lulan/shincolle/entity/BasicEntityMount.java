package com.lulan.shincolle.entity;

import java.util.List;

import javax.annotation.Nullable;

import com.lulan.shincolle.ShinColle;
import com.lulan.shincolle.ai.EntityAIShipAttackOnCollide;
import com.lulan.shincolle.ai.EntityAIShipFloating;
import com.lulan.shincolle.ai.EntityAIShipFollowOwner;
import com.lulan.shincolle.ai.EntityAIShipGuarding;
import com.lulan.shincolle.ai.EntityAIShipOpenDoor;
import com.lulan.shincolle.ai.EntityAIShipWander;
import com.lulan.shincolle.ai.path.ShipMoveHelper;
import com.lulan.shincolle.ai.path.ShipPathNavigate;
import com.lulan.shincolle.client.render.IShipCustomTexture;
import com.lulan.shincolle.entity.other.EntityAbyssMissile;
import com.lulan.shincolle.entity.other.EntityRensouhou;
import com.lulan.shincolle.handler.ConfigHandler;
import com.lulan.shincolle.init.ModItems;
import com.lulan.shincolle.init.ModSounds;
import com.lulan.shincolle.network.S2CEntitySync;
import com.lulan.shincolle.network.S2CSpawnParticle;
import com.lulan.shincolle.proxy.CommonProxy;
import com.lulan.shincolle.reference.ID;
import com.lulan.shincolle.reference.Values;
import com.lulan.shincolle.utility.CalcHelper;
import com.lulan.shincolle.utility.EntityHelper;
import com.lulan.shincolle.utility.ParticleHelper;
import com.lulan.shincolle.utility.TeamHelper;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;

/**MOUNT ENTITY
 * mount use cannon attack, no aircraft attack
 * all states get from host ex: sitting, leashed, sprinting...
 */
abstract public class BasicEntityMount extends EntityCreature implements IShipMount, IShipCannonAttack, IShipGuardian, IShipCustomTexture
{
	
	public BasicEntityShip host;  				//host
	protected ShipPathNavigate shipNavigator;	//水空移動用navigator
	protected ShipMoveHelper shipMoveHelper;
	protected int revengeTime;					//revenge target time
	protected int soundHurtDelay;
    
    //model display
    /**EntityState: 0:HP State 1:Emotion 2:Emotion2*/
	protected byte stateEmotion;				//表情1
	protected byte stateEmotion2;				//表情2
	protected int attackTime, attackTime2, startEmotion, startEmotion2;  //motion開始時間
	protected boolean headTilt;
	protected float[] seatPos;					//ship rider position
	protected float[] seatPos2;					//player rider position
	
	//AI
	protected double shipDepth;					//水深, 用於水中高度判定
	public int keyPressed;						//key(bit): 0:W 1:S 2:A 3:D 4:Jump
	public static boolean stopAI = false;		//stop onUpdate, onLivingUpdate
	
	
    public BasicEntityMount(World world)
    {
		super(world);
		this.isImmuneToFire = true;
		this.ignoreFrustumCheck = true;	//即使不在視線內一樣render
		this.stepHeight = 3F;
		this.shipNavigator = new ShipPathNavigate(this, world);
		this.shipMoveHelper = new ShipMoveHelper(this, 20F);
		
		//AI flag
		this.keyPressed = 0;
		this.soundHurtDelay = 0;
		this.attackTime = 0;
		this.attackTime2 = 0;
        this.stateEmotion = 0;
        this.stateEmotion2 = 0;
        this.startEmotion = 0;
        this.startEmotion2 = 0;
        this.headTilt = false;
	}
    
    /** init attributes */
    abstract public void initAttrs(BasicEntityShip host);
    
	//平常音效
    @Override
    @Nullable
    protected SoundEvent getAmbientSound()
    {
    	if (ConfigHandler.useWakamoto && rand.nextInt(30) == 0)
    	{
			return ModSounds.SHIP_WAKA_IDLE;
		}
		return null;
    }
    
	//受傷音效
    @Override
    @Nullable
    protected SoundEvent getHurtSound()
    {
		if (ConfigHandler.useWakamoto && rand.nextInt(30) == 0 && this.soundHurtDelay <= 0)
		{
			this.soundHurtDelay = 20 + this.getRNG().nextInt(40);
			return ModSounds.SHIP_WAKA_HURT;
		}
		return null;
    }

	//死亡音效
    @Override
    @Nullable
    protected SoundEvent getDeathSound()
    {
		if (ConfigHandler.useWakamoto)
		{
			return ModSounds.SHIP_WAKA_DEATH;
		}
		return null;
    }

	//音效大小
    @Override
	protected float getSoundVolume()
    {
		return ConfigHandler.volumeShip * 0.4F;
	}
	
    //音效音高
    @Override
	protected float getSoundPitch()
    {
    	return 1F;
    }
	
    //setup AI
	public void setAIList()
	{
		if (!this.world.isRemote)
		{
			this.clearAITasks();
			this.clearAITargetTasks();

			//high priority
			this.tasks.addTask(1, new EntityAIShipGuarding(this));				//0111
			this.tasks.addTask(2, new EntityAIShipFollowOwner(this));			//0111
			
			//use melee attack
			if (this.getStateFlag(ID.F.UseMelee))
			{
				this.tasks.addTask(12, new EntityAIShipAttackOnCollide(this, 1D));
			}
			
			//idle AI
			//moving
			this.tasks.addTask(21, new EntityAIShipOpenDoor(this, true));		//0000
			this.tasks.addTask(22, new EntityAIShipFloating(this));				//0111
			this.tasks.addTask(25, new EntityAIShipWander(this, 12, 7, 0.8D));	//0111
		}
	}
	
	//called client+server both side!!
	@Override
    public boolean attackEntityFrom(DamageSource attacker, float atk)
	{
		//disalbe inWall damage
		if (attacker.getDamageType() == "inWall")
		{
			return false;
		}
		
		if (attacker.getDamageType() == "fall")
		{
			return false;
		}
     
        //無敵的entity傷害無效
  		if (this.isEntityInvulnerable(attacker))
  		{	
        	return false;
        }
		
		//server side
		if (!this.world.isRemote)
		{
			if (host == null)
			{
				this.setDead();
				return false;
			}
			
			//set host hurt face
	    	if (this.host.getStateEmotion(ID.S.Emotion) != ID.Emotion.O_O)
	    	{
	    		this.host.setStateEmotion(ID.S.Emotion, ID.Emotion.O_O, true);
	    	}
	        
	        if (attacker.getEntity() != null)
	        {
	  			Entity entity = attacker.getEntity();
	  			
	  			//calc dodge
				float dist = (float) this.getDistanceSqToEntity(entity);
				if (EntityHelper.canDodge(this, dist))
				{
					return false;
				}
	  			
	  			//不會對自己造成傷害, 可免疫毒/掉落/窒息等傷害 (此為自己對自己造成傷害)
	  			if (entity.equals(this))
	  			{
	  				return false;
	  			}
	  			
	  			//若攻擊方為player, 則修正傷害
	  			if (entity instanceof EntityPlayer)
	  			{
					//若禁止friendlyFire, 則傷害設為0
					if (!ConfigHandler.friendlyFire)
					{
						return false;
					}
	  			}
	  			
	  			//進行def計算
				float reduceAtk = atk * (1F - (this.getDefValue() - rand.nextInt(20) + 10F) * 0.01F);    
				
				//ship vs ship, config傷害調整
				if (entity instanceof BasicEntityShip || entity instanceof BasicEntityAirplane || 
					entity instanceof EntityRensouhou || entity instanceof BasicEntityMount)
				{
					reduceAtk = reduceAtk * (float)ConfigHandler.dmgSummon * 0.01F;
				}
				
				//ship vs ship, damage type傷害調整
				if (entity instanceof IShipAttackBase)
				{
					//get attack time for damage modifier setting (day, night or ...etc)
					int modSet = this.world.provider.isDaytime() ? 0 : 1;
					reduceAtk = CalcHelper.calcDamageByType(reduceAtk, ((IShipAttackBase) entity).getDamageType(), this.getDamageType(), modSet);
				}
				
				//min damage設為1
		        if (reduceAtk < 0) reduceAtk = 0;
		        
		        //取消host的坐下動作
		        if (host != null)
		        {
		        	this.host.setSitting(false);
		        }
				
		        return super.attackEntityFrom(attacker, reduceAtk);
	  		}
		}
		
		return false;
	}
	
	//不跟rider碰撞
	@Override
  	protected void collideWithEntity(Entity target)
  	{
  		if (target.equals(this.getRidingEntity()) || this.isPassenger(target) ||
  			target instanceof BasicEntityAirplane)
  		{
  			return;
  		}
  		
  		target.applyEntityCollision(this);
    }
	
	@Override
	public EnumActionResult applyPlayerInteraction(EntityPlayer player, Vec3d vec, @Nullable ItemStack stack, EnumHand hand)
    {	
		ItemStack itemstack = player.inventory.getCurrentItem();  //get item in hand
		
		//use cake to change state
		if (itemstack != null && host != null)
		{
			//use cake
			if (itemstack.getItem() == Items.CAKE)
			{
				switch (host.getStateEmotion(ID.S.State))
				{
				case ID.State.NORMAL:	//普通轉騎乘
					host.setStateEmotion(ID.S.State, ID.State.EQUIP00, true);
					break;
				case ID.State.EQUIP00:	//騎乘轉普通
					host.setStateEmotion(ID.S.State, ID.State.NORMAL, true);
					this.clearRider();
					break;
				default:
					host.setStateEmotion(ID.S.State, ID.State.NORMAL, true);
					break;
				}
				
				this.host.setPositionAndUpdate(posX, posY + 1.5D, posZ);
				
				return EnumActionResult.SUCCESS;
			}
			
			//use lead
			if (itemstack.getItem() == Items.LEAD && this.canBeLeashedTo(player))
			{	
				this.setLeashedToEntity(player, true);
				return EnumActionResult.SUCCESS;
	        }
			
			//caress head mode: morale +3
			if (itemstack.getItem() == ModItems.PointerItem &&
				!this.world.isRemote && itemstack.getItemDamage() > 2)
			{
				//add little morale to host
				int t = this.host.ticksExisted - this.host.getMoraleTick();
				int m = this.host.getStateMinor(ID.M.Morale);
				
				if (t > 3 && m < 6100)
				{	//if caress > 3 ticks
					this.host.setMoraleTick(this.ticksExisted);
					this.host.setStateMinor(ID.M.Morale, m + 3);
				}
				
				//TODO show mounts emotion
				
				return EnumActionResult.SUCCESS;
			}
		}//end use item
		
		//如果已經被綑綁, 再點一下可以解除綑綁
		if (this.getLeashed() && this.getLeashedToEntity().equals(player))
		{
            this.clearLeashed(true, !player.capabilities.isCreativeMode);
            return EnumActionResult.SUCCESS;
        }

		//ride the mount if dist < 2.4 blocks, or set ship and mount sitting
		if (!this.world.isRemote && !player.isSneaking())
		{
			//ride mount
			if (TeamHelper.checkIsBanned(this, player) && this.getDistanceSqToEntity(player) < 6D)
			{
  	  			player.startRiding(this, true);
  	  			this.stateEmotion = 1;
  	  			this.sendSyncPacket(0);
  	  			
				return EnumActionResult.SUCCESS;
			}
			//set sitting
			else
			{
				//check owner
				if (TeamHelper.checkSameOwner(player, this.host))
				{
					this.host.setEntitySit(!this.host.isSitting());
					this.isJumping = false;
			        this.getShipNavigate().clearPathEntity();
			        this.getNavigator().clearPathEntity();
			        this.setAttackTarget(null);
			        this.setEntityTarget(null);

		            return EnumActionResult.SUCCESS;
				}
			}
        }
		
		//shift+right click時打開host GUI
		if (player.isSneaking() && !this.world.isRemote && TeamHelper.checkSameOwner(player, this.host))
		{  
			int eid = this.host.getEntityId();
    		FMLNetworkHandler.openGui(player, ShinColle.instance, ID.Gui.SHIPINVENTORY, this.world, this.host.getEntityId(), 0, 0);
    		return EnumActionResult.SUCCESS;
		}
		
		return EnumActionResult.PASS;
  	}
	
	@Override
    public void onLivingUpdate()
    {
    	if ((!world.isRemote))
    	{
        	//update movement, NOTE: 1.9.4: must done before vanilla MoveHelper updating in super.onLivingUpdate()
        	EntityHelper.updateShipNavigator(this);
            super.onLivingUpdate();
    	}
    	else
    	{
    		super.onLivingUpdate();
    	}
    }
	
	@Override
	public void onUpdate()
	{
		if (stopAI)
		{
			return;
		}
		
		super.onUpdate();
		
		if (soundHurtDelay > 0) soundHurtDelay--;

		//apply movement by key pressed
		if (this.host != null && !host.getStateFlag(ID.F.NoFuel) && this.keyPressed > 0 &&
			this.getControllingPassenger() instanceof EntityPlayer)
		{
			EntityPlayer rider2 = (EntityPlayer) this.getControllingPassenger();
			float yaw = rider2.rotationYawHead % 360F * Values.N.DIV_PI_180;
			float pitch = rider2.rotationPitch % 360F * Values.N.DIV_PI_180;
			
			this.applyMovement(pitch, yaw);
			this.rotationYaw = rider2.rotationYaw;
		}
			
		//check depth
		EntityHelper.checkDepth(this);

		//client side
		if (this.world.isRemote)
		{
			if (shipDepth > 0D)
			{
				//水中移動時, 產生水花特效
				//(注意此entity因為設為非高速更新, client端不會更新motionX等數值, 需自行計算)
				double motX = this.posX - this.prevPosX;
				double motZ = this.posZ - this.prevPosZ;
				double parH = this.posY - (int)this.posY;
				double limit = 0.25D;
				
				if (motX > limit) motX = limit;
				else if (motX < -limit) motX = -limit;
				
				if (motZ > limit) motZ = limit;
				else if (motZ < -limit) motZ = -limit;
				
				if (motX != 0 || motZ != 0)
				{
					ParticleHelper.spawnAttackParticleAt(this.posX + motX*1.5D, this.posY, this.posZ + motZ*1.5D, 
							-motX*0.5D, 0D, -motZ*0.5D, (byte)15);
				}
			}
		}
		//server side
		else
		{
			//owner消失(通常是server restart)
			if (this.host == null)
			{
				this.setDead();
			}
			else
			{		
				//owner還在, 但是已經騎乘別人, 則消除此entity
				if(this.host.getRidingEntity() != this)
				{
					this.setDead();
				}
				
				//get target every 8 ticks
				if (this.ticksExisted % 8 == 0)
				{
					this.setEntityTarget(this.host.getEntityTarget());
					
					//check every 16 ticks
					if (this.ticksExisted % 16 == 0)
					{
						//waypoint move
	            		if (EntityHelper.updateWaypointMove(this))
	            		{
	            			shipNavigator.tryMoveToXYZ(getGuardedPos(0), getGuardedPos(1), getGuardedPos(2), 1D);
	            			host.sendSyncPacket(S2CEntitySync.PID.SyncShip_Guard, true);
	            		}
	            		
	            		//check every 128 ticks
    					if (this.ticksExisted % 128 == 0)
    					{
    						this.sendSyncPacket(0);

    						//update attribute
    						setupAttrs();
    						
    						//防止溺死
    						if (this.isInWater())
    						{
    							this.setAir(300);
    						}
    					}//end every 128 ticks
					}//end every 16 ticks
				}//end every 8 ticks
			}
		}
	}
	
	//set movement by key pressed, pitch/yaw is RAD not DEGREE
	private void applyMovement(float pitch, float yaw)
	{
		//calc move direction by yaw
		final float movSpeed = this.getMoveSpeed();
		float[] movez = CalcHelper.rotateXZByAxis(movSpeed, 0F, yaw, 1F);	//前後
		float[] movex = CalcHelper.rotateXZByAxis(0F, movSpeed, yaw, 1F);	//左右
		
		if (this.onGround || EntityHelper.checkEntityIsInLiquid(this))
		{
			//horizontal move, 至少要4 tick才能加到最高速
			//W (bit 1)
			if ((keyPressed & 1) > 0)
			{
				motionX += movez[1] / 4F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movez[1])) motionX = movez[1];
				motionZ += movez[0] / 4F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movez[0])) motionZ = movez[0];
			}
			
			//S (bit 2)
			if ((keyPressed & 2) > 0)
			{
				motionX -= movez[1] / 4F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movez[1])) motionX = -movez[1];
				motionZ -= movez[0] / 4F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movez[0])) motionZ = -movez[0];
			}
			
			//A (bit 3)
			if ((keyPressed & 4) > 0)
			{
				motionX += movex[1] / 4F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movex[1])) motionX = movex[1];
				motionZ += movex[0] / 4F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movex[0])) motionZ = movex[0];
			}
			
			//D (bit 4)
			if ((keyPressed & 8) > 0)
			{
				motionX -= movex[1] / 4F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movex[1])) motionX = -movex[1];
				motionZ -= movex[0] / 4F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movex[0])) motionZ = -movex[0];
			}
			
			//vertical move
			if (pitch > 0.5F)
			{	//move down
				motionY += -0.1F;
				if(motionY < -movSpeed / 2F) motionY = -movSpeed / 2F;			
			}
			
			if (pitch < -1F)
			{	//move up
				motionY += 0.1F;
				if(motionY > movSpeed / 2F) motionY = movSpeed / 2F;
			}
			
			//若水平撞到東西, 則嘗試跳跳
			if (this.isCollidedHorizontally)
			{
				this.motionY += 0.4D;
			}
			
			//jump (bit 5)
			if ((keyPressed & 16) > 0)
			{
				this.motionY += movSpeed * 2F;
				if (motionY > 1F) motionY = 1F;
				//reset jump flag
				keyPressed -= 16;
			}	
		}
		else
		{
			//不在地面時, 各方向的空中加速度不同, 往前不變, 往後減少, 左右大幅減少
			//W (bit 1)
			if ((keyPressed & 1) > 0)
			{
				motionX += movez[1] / 4F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movez[1])) motionX = movez[1];
				motionZ += movez[0] / 4F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movez[0])) motionZ = movez[0];
			}
			
			//S (bit 2)
			if ((keyPressed & 2) > 0)
			{
				motionX -= movez[1] / 16F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movez[1])) motionX = -movez[1];
				motionZ -= movez[0] / 16F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movez[0])) motionZ = -movez[0];
			}
			
			//A (bit 3)
			if ((keyPressed & 4) > 0)
			{
				motionX += movex[1] / 32F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movex[1])) motionX = movex[1];
				motionZ += movex[0] / 32F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movex[0])) motionZ = movex[0];
			}
			
			//D (bit 4)
			if((keyPressed & 8) > 0)
			{
				motionX -= movex[1] / 32F;
				if (MathHelper.abs((float) motionX) > MathHelper.abs(movex[1])) motionX = -movex[1];
				motionZ -= movex[0] / 32F;
				if (MathHelper.abs((float) motionZ) > MathHelper.abs(movex[0])) motionZ = -movex[0];
			}
		}
	}

	@Override
	public byte getStateEmotion(int id)
	{
		return id == ID.S.Emotion ? stateEmotion : stateEmotion2;
	}
	
	@Override
	public void setStateEmotion(int id, int value, boolean sync)
	{	
		switch (id)
		{
		case ID.S.Emotion:
			stateEmotion = (byte) value;
			break;
		case ID.S.Emotion2:
			stateEmotion2 = (byte) value;
			break;
		default:
			break;
		}
		
		if (sync && !world.isRemote)
		{
			TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 32D);
			CommonProxy.channelE.sendToAllAround(new S2CEntitySync(this, S2CEntitySync.PID.SyncEntity_Emo), point);
		}
	}
	
	@Override
	public void setShipDepth(double par1)
	{
		this.shipDepth = par1;
	}

	@Override
	public boolean getStateFlag(int flag)
	{	
		if (this.host != null) return this.host.getStateFlag(flag);
		return false;
	}

	@Override
	public void setStateFlag(int id, boolean flag)
	{
		if (this.host != null) this.host.setStateFlag(id, flag);
	}

	@Override
	public int getFaceTick()
	{
		return this.startEmotion;
	}

	@Override
	public int getHeadTiltTick()
	{
		return this.startEmotion2;
	}

	@Override
	public void setFaceTick(int par1)
	{
		this.startEmotion = par1;
	}

	@Override
	public void setHeadTiltTick(int par1)
	{
		this.startEmotion2 = par1;
	}

	@Override
	public int getTickExisted()
	{
		return this.ticksExisted;
	}

	@Override
	public int getAttackTick()
	{
		return this.attackTime;
	}
  	
    //clear AI
  	protected void clearAITasks()
  	{
  		this.tasks.taskEntries.clear();
  	}
  	
  	//clear target AI
  	protected void clearAITargetTasks()
  	{
  		this.targetTasks.taskEntries.clear();
  	}
    
  	@Override
	public Entity getEntityTarget()
  	{
  		if (this.host != null) return this.host.getEntityTarget();
		return null;
	}
  	
  	@Override
	public void setEntityTarget(Entity target)
  	{
  		if (this.host != null) this.host.setEntityTarget(target);
	}
  	
  	/** attack particle at attacker
  	 * 
  	 *  type: 0:melee, 1:light cannon, 2:heavy cannon, 3:light air, 4:heavy air
  	 *  vec: 0:distX, 1:distY, 2:distZ, 3:dist sqrt
  	 */
  	public void applyParticleAtAttacker(int type, Entity target, float[] vec)
  	{
  		TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64D);
        
  		switch (type)
  		{
  		case 1:  //light cannon
  			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 19, this.posX, this.posY+1.5D, this.posZ, vec[0], 1.2F, vec[2], true), point);
  			//發送攻擊flag給host, 設定其attack time
  			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this.host, 0, true), point);
  		break;
  		case 2:  //heavy cannon
  		case 3:  //light aircraft
  		case 4:  //heavy aircraft
		default: //melee
			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 0, true), point);
			//發送攻擊flag給host, 設定其attack time
  			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this.host, 0, true), point);
		break;
  		}
  	}
  	
  	/** attack particle at target
  	 * 
  	 *  type: 0:melee, 1:light cannon, 2:heavy cannon, 3:light air, 4:heavy air
  	 *  vec: 0:distX, 1:distY, 2:distZ, 3:dist sqrt
  	 */
  	public void applyParticleAtTarget(int type, Entity target, float[] vec)
  	{
  		TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64D);
  		
  		switch (type)
  		{
  		case 1:  //light cannon
			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(target, 9, false), point);
  		break;
  		case 2:  //heavy cannon
  		break;
  		case 3:  //light aircraft
  		break;
  		case 4:  //heavy aircraft
  		break;
		default: //melee
    		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(target, 1, false), point);
		break;
  		}
  	}
  	
  	/** attack particle at attacker
  	 * 
  	 *  type: 0:melee, 1:light cannon, 2:heavy cannon, 3:light air, 4:heavy air
  	 */
  	public void applySoundAtAttacker(int type, Entity target)
  	{
  		switch (type)
  		{
  		case 1:  //light cannon
  			this.playSound(ModSounds.SHIP_FIRELIGHT, ConfigHandler.volumeFire, this.getSoundPitch() * 0.85F);
  	        
  			//entity sound
  			if (this.rand.nextInt(10) > 8)
  			{
  				this.playSound(ModSounds.SHIP_WAKA_ATTACK, this.getSoundVolume(), this.getSoundPitch());
  	        }
  		break;
  		case 2:  //heavy cannon
  			this.playSound(ModSounds.SHIP_FIREHEAVY, ConfigHandler.volumeFire, this.getSoundPitch() * 0.85F);
  	        
  	        //entity sound
  	        if (this.getRNG().nextInt(10) > 8)
  	        {
  	        	this.playSound(ModSounds.SHIP_WAKA_ATTACK, this.getSoundVolume(), this.getSoundPitch());
  	        }
  		break;
  		case 3:  //light aircraft
  		case 4:  //heavy aircraft
  			this.playSound(ModSounds.SHIP_AIRCRAFT, ConfigHandler.volumeFire * 0.5F, this.getSoundPitch() * 0.85F);
  	  	break;
		default: //melee
			if (this.getRNG().nextInt(2) == 0)
			{
				this.playSound(ModSounds.SHIP_WAKA_ATTACK, this.getSoundVolume(), this.getSoundPitch());
	        }
		break;
  		}//end switch
  	}
  	
  	/** special particle at entity
  	 * 
  	 *  type: 0:miss, 1:critical, 2:double hit, 3:triple hit
  	 */
  	protected void applyParticleSpecialEffect(int type)
  	{
  		TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64D);
  		
  		switch (type)
  		{
  		case 1:  //critical
      		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 11, false), point);
  			break;
  		case 2:  //double hit
      		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 12, false), point);
  			break;
  		case 3:  //triple hit
      		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 13, false), point);
  			break;
		default: //miss
      		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 10, false), point);
			break;
  		}
  	}
  	
  	/** attack particle at target
  	 * 
  	 *  type: 0:melee, 1:light cannon, 2:heavy cannon, 3:light air, 4:heavy air
  	 */
  	public void applySoundAtTarget(int type, Entity target)
  	{
  		switch (type)
  		{
  		case 1:  //light cannon
  		break;
  		case 2:  //heavy cannon
  		break;
  		case 3:  //light aircraft
  		break;
  		case 4:  //heavy aircraft
  		break;
		default: //melee
		break;
  		}
  	}
  	
  	/** attack base damage
  	 *  type: 0:melee, 1:light cannon, 2:heavy cannon, 3:light air, 4:heavy air
  	 */
  	public float getAttackBaseDamage(int type, Entity target)
  	{
  		switch (type)
  		{
  		case 1:  //light cannon
  			return CalcHelper.calcDamageBySpecialEffect(this, target, this.host.getStateFinal(ID.ATK), 0);
  		case 2:  //heavy cannon
  			return this.host.getStateFinal(ID.ATK_H);
  		case 3:  //light aircraft
  			return this.host.getStateFinal(ID.ATK_AL);
  		case 4:  //heavy aircraft
  			return this.host.getStateFinal(ID.ATK_AH);
		default: //melee
			return this.host.getStateFinal(ID.ATK);	//mounts can deal 100% melee attack
  		}
  	}
  	
  	//change melee damage to 100%
  	@Override
  	public boolean attackEntityAsMob(Entity target)
  	{
  		//get attack value
  		float atk = getAttackBaseDamage(0, target);
  				
  		//experience++
  		this.host.addShipExp(ConfigHandler.expGain[0]);
  		
  		//attack time
  		this.host.setCombatTick(this.ticksExisted);
  		
  		//entity attack effect
	    applySoundAtAttacker(0, target);
	    applyParticleAtAttacker(0, target, new float[4]);
  		
  	    //將atk跟attacker傳給目標的attackEntityFrom方法, 在目標class中計算傷害
  	    //並且回傳是否成功傷害到目標
  	    boolean isTargetHurt = target.attackEntityFrom(DamageSource.causeMobDamage(this), atk);

  	    //if attack success
  	    if (isTargetHurt)
  	    {
  	    	applySoundAtTarget(0, target);
	        applyParticleAtTarget(0, target, new float[4]);
			this.host.applyEmotesReaction(3);
			
			if (ConfigHandler.canFlare) this.host.flareTarget(target);
  	    }

  	    return isTargetHurt;
  	}
    
  	//light attack
    @Override
	public boolean attackEntityWithAmmo(Entity target)
    {
    	//ammo--
        if (!this.host.decrAmmoNum(0, this.host.getAmmoConsumption())) return false;
        
        //experience++
        this.host.addShipExp(ConfigHandler.expGain[1]);
  		
  		//grudge--
        this.host.decrGrudgeNum(ConfigHandler.consumeGrudgeAction[ID.ShipConsume.LAtk]);
		
  		//combat time
        this.host.setCombatTick(this.ticksExisted);
  		
  		//get attack value
		float atkLight = getAttackBaseDamage(1, target);
        
        //calc dist to target
        float[] distVec = new float[4];  //x, y, z, dist
        distVec[0] = (float) (target.posX - this.posX);
        distVec[1] = (float) (target.posY - this.posY);
        distVec[2] = (float) (target.posZ - this.posZ);
        distVec[3] = MathHelper.sqrt(distVec[0]*distVec[0] + distVec[1]*distVec[1] + distVec[2]*distVec[2]);
        distVec[0] = distVec[0] / distVec[3];
        distVec[1] = distVec[1] / distVec[3];
        distVec[2] = distVec[2] / distVec[3];
        
        //play cannon fire sound at attacker
        applySoundAtAttacker(1, target);
	    applyParticleAtAttacker(1, target, distVec);
	    
		//calc miss chance, if not miss, calc cri/multi hit
		TargetPoint point = new TargetPoint(this.dimension, this.host.posX, this.host.posY, this.host.posZ, 64D);
        float missChance = 0.2F + 0.15F * (distVec[3] / host.getStateFinal(ID.HIT)) - 0.001F * host.getLevel();
        missChance -= this.host.getEffectEquip(ID.EF_MISS);		//equip miss reduce
        if (missChance > 0.35F) missChance = 0.35F;	//max miss chance
  		
        //calc miss chance
        if (this.rand.nextFloat() < missChance)
        {
        	atkLight = 0;	//still attack, but no damage
        	this.host.applyParticleSpecialEffect(0);
        }
        else
        {
        	//roll cri -> roll double hit -> roll triple hit (triple hit more rare)
        	//calc critical
        	if (this.rand.nextFloat() < this.host.getEffectEquip(ID.EF_CRI))
        	{
        		atkLight *= 1.5F;
        		this.host.applyParticleSpecialEffect(1);
        	}
        	else
        	{
        		//calc double hit
            	if (this.rand.nextFloat() < this.host.getEffectEquip(ID.EF_DHIT))
            	{
            		atkLight *= 2F;
            		this.host.applyParticleSpecialEffect(2);
            	}
            	else
            	{
            		//calc double hit
                	if (this.rand.nextFloat() < this.host.getEffectEquip(ID.EF_THIT))
                	{
                		atkLight *= 3F;
                		this.host.applyParticleSpecialEffect(3);
                	}
            	}
        	}
        }
        
        //vs player = 25% dmg
  		if (target instanceof EntityPlayer)
  		{
  			atkLight *= 0.25F;
  			
  			//check friendly fire
    		if (!ConfigHandler.friendlyFire)
    		{
    			atkLight = 0F;
    		}
    		else if (atkLight > 59F)
    		{
    			atkLight = 59F;	//same with TNT
    		}
  		}

	    //確認攻擊是否成功
	    boolean isTargetHurt = target.attackEntityFrom(DamageSource.causeMobDamage(this).setProjectile(), atkLight);
	    		
	    //if attack success
	    if (isTargetHurt)
	    {
	    	applySoundAtTarget(1, target);
	        applyParticleAtTarget(1, target, distVec);
	        this.host.applyEmotesReaction(3);
	        
	        if (ConfigHandler.canFlare) this.host.flareTarget(target);
        }
	    
	    return isTargetHurt;
	}

	@Override
	public boolean attackEntityWithHeavyAmmo(Entity target)
	{
		//ammo--
        if (!this.host.decrAmmoNum(1, this.host.getAmmoConsumption())) return false;
		
        //experience++
      	this.host.addShipExp(ConfigHandler.expGain[2]);
      		
      	//grudge--
      	this.host.decrGrudgeNum(ConfigHandler.consumeGrudgeAction[ID.ShipConsume.HAtk]);
      	
  		//attack time
      	this.host.setCombatTick(this.ticksExisted);
		
		//get attack value
		float atkHeavy = getAttackBaseDamage(2, target);
		float kbValue = 0.15F;

		//飛彈是否採用直射
		boolean isDirect = false;
		float launchPos = (float) posY + height * 0.75F;
				
		//計算目標距離
		float[] distVec = new float[4];
		float tarX = (float) target.posX;
		float tarY = (float) target.posY;
		float tarZ = (float) target.posZ;
		
		distVec[0] = tarX - (float) this.posX;
        distVec[1] = tarY - (float) this.posY;
        distVec[2] = tarZ - (float) this.posZ;
		distVec[3] = MathHelper.sqrt(distVec[0]*distVec[0] + distVec[1]*distVec[1] + distVec[2]*distVec[2]);
        
        //超過一定距離/水中 , 則採用拋物線,  在水中時發射高度較低
        if (distVec[3] < 7F)
        {
        	isDirect = true;
        }
		
        if (getShipDepth() > 0D)
        {
        	isDirect = true;
        	launchPos = (float) posY;
        }
        
        //play sound and particle
        applySoundAtAttacker(2, target);
	    applyParticleAtAttacker(2, target, distVec);
        
        //calc miss chance, miss: add random offset(0~6) to missile target 
        float missChance = 0.2F + 0.15F * (distVec[3] / host.getStateFinal(ID.HIT)) - 0.001F * host.getLevel();
        missChance -= this.host.getEffectEquip(ID.EF_MISS);	//equip miss reduce
        if (missChance > 0.35F) missChance = 0.35F;	//max miss chance = 30%
		
        //calc miss chance
        if (this.rand.nextFloat() < missChance)
        {
        	tarX = tarX - 5F + this.rand.nextFloat() * 10F;
        	tarY = tarY + this.rand.nextFloat() * 5F;
      	  	tarZ = tarZ - 5F + this.rand.nextFloat() * 10F;
      	  	
      	  	applyParticleSpecialEffect(0);  //miss particle
        }

        //spawn missile
        EntityAbyssMissile missile = new EntityAbyssMissile(this.world, this.host, 
        		tarX, tarY+target.height*0.2F, tarZ, launchPos, atkHeavy, kbValue, isDirect, -1F);
        this.world.spawnEntity(missile);
  		
        //play target effect
        applySoundAtTarget(2, target);
        applyParticleAtTarget(2, target, distVec);
        this.host.applyEmotesReaction(3);
        
        if (ConfigHandler.canFlare) this.host.flareTarget(target);
        
        return true;
	}
	
	/**修改移動方法, 使其water跟lava中移動時像是flying entity
     * Moves the entity based on the specified heading.  Args: strafe, forward
     */
	@Override
    public void moveEntityWithHeading(float strafe, float forward)
	{
		EntityHelper.moveEntityWithHeading(this, strafe, forward);
    }
	
	@Override
	public int getLevel()
	{
		if (this.host != null) return this.host.getLevel();
		return 150;
	}

	@Override
	public float getAttackSpeed()
	{
		if (this.host != null) return this.host.getStateFinal(ID.SPD);
		return 0F;
	}

	@Override
	public float getAttackRange()
	{
		if (this.host != null) return this.host.getStateFinal(ID.HIT);
		return 0F;
	}
	
	@Override
	public float getMoveSpeed()
	{
		if (this.host != null)
		{
			if (!this.getIsSitting() && !this.host.isSitting())
			{
				return this.host.getStateFinal(ID.MOV);
			}
		}
			
		return 0F;
	}
	
	@Override
	public boolean getIsLeashed()
	{
		//綁住host或者自己都算綁住
		if (this.host != null)
		{
			return this.host.getIsLeashed() || this.getLeashed();
		}
		return false;
	}
	
	@Override
	public int getStateMinor(int id)
	{
		if (this.host != null) return this.host.getStateMinor(id);
		return 0;
	}

	@Override
	public void setStateMinor(int state, int par1)
	{
		if (this.host != null) this.host.setStateMinor(state, par1);
	}
	
	@Override
	public int getAmmoLight()
	{
		if (this.host != null) return this.host.getAmmoLight();
		return 0;
	}

	@Override
	public int getAmmoHeavy()
	{
		if (this.host != null) return this.host.getAmmoHeavy();
		return 0;
	}
	
	@Override
	public boolean useAmmoLight()
	{
		if (this.host != null) return this.host.useAmmoLight();
		return false;
	}

	@Override
	public boolean useAmmoHeavy()
	{
		if (this.host != null) return this.host.useAmmoHeavy();
		return false;
	}

	@Override
	public boolean hasAmmoLight()
	{
		return this.getAmmoLight() > 0;
	}

	@Override
	public boolean hasAmmoHeavy()
	{
		return this.getAmmoHeavy() > 0;
	}

	@Override
	public void setAmmoLight(int num) {}	//no use

	@Override
	public void setAmmoHeavy(int num) {}	//no use

	@Override
	public float getAttackDamage()			//no use
	{
		if (this.host != null) return this.host.getAttackDamage();
		return 0F;
	}
	
	@Override
	public boolean getAttackType(int par1)
	{
		if (this.host != null) return this.host.getAttackType(par1);
		return true;
	}
	
	@Override
	public float getEffectEquip(int id)
	{
		if (this.host != null) return this.host.getEffectEquip(id);
		return 0F;
	}
	
	@Override
	public float getDefValue()
	{
		if (this.host != null) return this.host.getStateFinal(ID.DEF) * 0.5F;
		return 0F;
	}

	@Override
	public boolean getIsRiding()
	{
		return false;
	}

	@Override
	public boolean getIsSprinting()
	{
		return false;
	}

	@Override
	public boolean getIsSitting()
	{
		if (this.host != null) return this.host.getIsSitting();
		return false;
	}

	@Override
	public boolean getIsSneaking()
	{
		return false;
	}

	@Override
	public double getShipDepth()
	{
		return this.shipDepth;
	}
	
	@Override
	public boolean shouldDismountInWater(Entity rider)
	{
        return false;
    }

	@Override
	public ShipPathNavigate getShipNavigate()
	{
		return this.shipNavigator;
	}

	@Override
	public ShipMoveHelper getShipMoveHelper()
	{
		return this.shipMoveHelper;
	}

	@Override
	public boolean canFly()
	{
		return isPotionActive(MobEffects.LEVITATION);
	}
	
	@Override
	public boolean canBreatheUnderwater()
	{
		return true;
	}
	
    @Nullable
    @Override
    public Entity getControllingPassenger()
    {
    	for (Entity rider : this.getPassengers())
    	{
    		if (rider instanceof EntityPlayer) return rider;
    	}

        return null;
    }
	
	//dismount all rider and clear this mount entity
	public void clearRider()
	{
		List<Entity> riders = this.getPassengers();
		
		for (int i = 0; i < riders.size(); i++)
		{
			Entity rider = riders.get(i);
			if (rider != null) rider.dismountRidingEntity();
		}
		
		this.setDead();
	}
	
	//dismount rider2 only (player)
	public void clearRider2()
	{
		//reset mount emotion
		this.setStateEmotion(ID.S.Emotion, 0, false);
		
		//clear rider except host rider (ship)
		List<Entity> riders = this.getPassengers();
		
		for (Entity rider : riders)
		{
			if (rider != null) rider.dismountRidingEntity();
		}
		
		//若是server則發送sync packet
		if (!this.world.isRemote)
		{
			this.sendSyncPacket(1);
		}
	}
	
	//update attribute
	public void setupAttrs()
	{
		getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(this.host.getStateFinal(ID.HP) * 0.5D);
		getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(this.getMoveSpeed());
		getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(64);
		getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).setBaseValue((float) host.getLevel() * 0.005F);
	}
	
	@Override
	public void setEntitySit(boolean sit)
	{
		if (this.host != null) host.setEntitySit(sit);
	}
	
	/** sync packet
	 *  0: sync all rider
	 *
	 */
	public void sendSyncPacket(int type)
	{
		if (!world.isRemote)
		{
			TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 48D);
			
			switch (type)
			{
			case 0:
				CommonProxy.channelE.sendToAllAround(new S2CEntitySync(this, S2CEntitySync.PID.SyncShip_Riders), point);
			break;
			}
		}
	}
	
	@Override
	public Entity getGuardedEntity()
	{
		if (this.host != null) return this.host.getGuardedEntity();
		return null;
	}

	@Override
	public void setGuardedEntity(Entity entity)
	{
		if (this.host != null) this.host.setGuardedEntity(entity);
	}

	@Override
	public int getGuardedPos(int vec)
	{
		if (this.host != null) return this.host.getGuardedPos(vec);
		return -1;
	}

	@Override
	public void setGuardedPos(int x, int y, int z, int dim, int type)
	{
		if (this.host != null) this.host.setGuardedPos(x, y, z, dim, type);
	}
	
    @Override
    public float getModelRotate(int par1)
    {
    	return 0F;
    }
    
    //set model rotate angle, par1 = 0:X, 1:Y, 2:Z
    @Override
	public void setModelRotate(int par1, float par2) {}
    
    @Override
	public int getPlayerUID()
    {
		if (this.host != null) return this.host.getPlayerUID();
		return -1;
	}

	@Override
	public void setPlayerUID(int uid) {}
	
	@Override
	public Entity getHostEntity()
	{
		return this.host;
	}
	
	@Override
	public Entity getEntityRevengeTarget()
	{
		if (this.host != null) return this.host.getEntityRevengeTarget();
		return null;
	}

	@Override
	public int getEntityRevengeTime()
	{
		if (this.host != null) return this.host.getEntityRevengeTime();
		return 0;
	}

	@Override
	public void setEntityRevengeTarget(Entity target)
	{
		if (this.host != null) this.host.setEntityRevengeTarget(target);
	}
  	
  	@Override
	public void setEntityRevengeTime()
  	{
		this.revengeTime = this.ticksExisted;
	}
  	
  	@Override
    public double getMountedYOffset()
    {
  		return this.height;
    }
  	
  	//對第一個rider設定座位位置以及旋轉角度使其跟座騎一致, 對第二個以後rider只設定位置
  	@Override
  	public void updatePassenger(Entity passenger)
  	{
  		if (passenger != null && this.host != null && this.isPassenger(passenger))
  		{
  			//host ship rider
  	        if (this.host.equals(passenger))
  	        {
  	        	//set position
  	        	float[] ridePos = CalcHelper.rotateXZByAxis(this.seatPos[0], this.seatPos[1], renderYawOffset * Values.N.DIV_PI_180, 1F);	
  	            passenger.setPosition(this.posX + ridePos[1], this.posY + this.seatPos[2] + passenger.getYOffset(), this.posZ + ridePos[0]);
  	        }
  	        //player rider
  	        else
  	        {
  	        	//set position
  				float[] ridePos = CalcHelper.rotateXZByAxis(this.seatPos2[0], this.seatPos2[1], renderYawOffset * Values.N.DIV_PI_180, 1F);	
  	        	passenger.setPosition(this.posX + ridePos[1], this.posY + this.seatPos2[2] + passenger.getYOffset(), this.posZ + ridePos[0]);
  	        }
  	        
			//若有controlling rider且按下移動按鍵時, 則改為controlling rider朝向
  	        EntityPlayer player = (EntityPlayer) this.getControllingPassenger();
			if (this.keyPressed != 0 && player != null)
			{
				this.rotationYawHead = player.rotationYawHead;
				this.prevRotationYaw = player.prevRotationYaw;
				this.rotationYaw = player.rotationYaw;
				this.renderYawOffset = player.renderYawOffset;

				//清除AI自動走路, 以免妨礙玩家控制移動
				this.getShipNavigate().clearPathEntity();
			}
			
			//set host rider angle
			this.host.rotationYawHead = this.rotationYawHead;
			this.host.prevRotationYaw = this.prevRotationYaw;
			this.host.rotationYaw = this.rotationYaw;
			this.host.renderYawOffset = this.renderYawOffset;
  		}
    }
  	
  	@Override
	public int getAttackTick2()
  	{
		return this.attackTime2;
	}
  	
  	@Override
	public void setAttackTick(int par1)
  	{
  		this.attackTime = par1;
  	}

	@Override
	public void setAttackTick2(int par1)
	{
		this.attackTime2 = par1;
	}
	
	@Override
	public float getSwingTime(float partialTick)
	{
		return this.getSwingProgress(partialTick);
	}
	
	//get last waypoint, for waypoint loop checking
  	@Override
  	public BlockPos getLastWaypoint()
  	{
  		if (this.host != null) return this.host.getLastWaypoint();
  		return BlockPos.ORIGIN;
  	}
  	
  	@Override
  	public void setLastWaypoint(BlockPos pos)
  	{
  		if (this.host != null) this.host.setLastWaypoint(pos);
  	}
  	
	@Override
	public int getWpStayTime()
	{
		if (this.host != null) return host.getStateTimer(ID.T.WpStayTime);
		return 0;
	}

	@Override
	public int getWpStayTimeMax()
	{
		if (this.host != null) return host.getWpStayTimeMax();
		return 0;
	}

	@Override
	public void setWpStayTime(int time)
	{
		if (this.host != null) host.setStateTimer(ID.T.WpStayTime, time);
	}
	
	//for model display
	@Override
	public int getRidingState()
	{
		return 0;
	}
	
	@Override
	public void setRidingState(int state) {}
    
	@Override
	public int getDamageType()
	{
		if (this.host != null) return this.host.getDamageType();
		return 0;
	}
	
	@Override
	public boolean isJumping()
	{
		return this.isJumping;
	}
	
	
}